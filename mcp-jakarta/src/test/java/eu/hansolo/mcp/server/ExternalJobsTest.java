package eu.hansolo.mcp.server;

import eu.hansolo.mcp.api.Caller;
import eu.hansolo.mcp.api.ExternalJob;
import eu.hansolo.mcp.api.ExternalJobStatus;
import eu.hansolo.mcp.api.McpToolHandler;
import eu.hansolo.mcp.api.ToolCall;
import eu.hansolo.mcp.api.ToolResult;
import eu.hansolo.mcp.auth.AuthConfig;
import eu.hansolo.mcp.protocol.ErrorCodes;
import eu.hansolo.mcp.protocol.McpProtocolException;
import eu.hansolo.mcp.tasks.InMemoryTaskStore;
import eu.hansolo.mcp.tasks.TaskRecord;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/** Tasks that follow a job in another system: polling, claiming, failures, callbacks, cancellation and the wire. */
class ExternalJobsTest {

    private static final Caller ALICE = new Caller("alice", Set.of(), JsonObject.EMPTY_JSON_OBJECT);
    private static final Caller BOB   = new Caller("bob", Set.of(), JsonObject.EMPTY_JSON_OBJECT);
    private static final String TASKS = "io.modelcontextprotocol/tasks";

    private final TestClock         clock = new TestClock();
    private final InMemoryTaskStore store = new InMemoryTaskStore();
    private final ScriptedJob       job   = new ScriptedJob();


    @Test
    void theJobIsAskedAboutWhenDueAndTheTaskEndsWithItsResultAndLosesItsHandle() {
        TaskService service = service(job);
        String id = id(service.createExternal(ToolResult.externalTask(handle("build-42")), call(ALICE)));
        assertEquals("working", service.get(id, ALICE).getString("status"));
        assertEquals("build-42", store.find(id).orElseThrow().handle().getString("job"));
        assertNull(store.find(id).orElseThrow().workerId(), "no worker: nothing runs this task");

        job.script.add(ExternalJobStatus.running("compiling"));
        job.script.add(ExternalJobStatus.completed(ToolResult.text("passed")));

        service.pollExternal();
        assertEquals(0, job.polls.get(), "not due yet");

        clock.advance(5_000);
        service.pollExternal();
        assertEquals(1, job.polls.get());
        JsonObject working = service.get(id, ALICE);
        assertEquals("working", working.getString("status"));
        assertEquals("compiling", working.getString("statusMessage"));

        service.pollExternal();
        assertEquals(1, job.polls.get(), "asked again only after the poll interval");

        clock.advance(5_000);
        service.pollExternal();
        JsonObject done = service.get(id, ALICE);
        assertEquals("completed", done.getString("status"));
        assertEquals("passed", done.getJsonObject("result").getJsonArray("content").getJsonObject(0).getString("text"));
        assertEquals(List.of("build-42", "build-42"), job.handlesSeen);
        TaskRecord stored = store.find(id).orElseThrow();
        assertNull(stored.handle(), "the handle may hold credentials");
        assertNull(stored.nextPollAt());

        clock.advance(60_000);
        service.pollExternal();
        assertEquals(2, job.polls.get(), "a finished task is not asked about");
    }

    @Test
    void theJobCanAskToBeAskedAboutLaterButNeverSoonerThanTheInterval() {
        TaskService service = service(job);
        String id = id(service.createExternal(ToolResult.externalTask(handle("slow")), call(ALICE)));
        job.script.add(ExternalJobStatus.running(null, 20_000));
        job.script.add(ExternalJobStatus.running(null, 1));
        clock.advance(5_000);
        service.pollExternal();
        clock.advance(15_000);
        service.pollExternal();
        assertEquals(1, job.polls.get(), "20 s were asked for");
        clock.advance(5_000);
        service.pollExternal();
        assertEquals(2, job.polls.get());
        clock.advance(4_999);
        service.pollExternal();
        assertEquals(2, job.polls.get(), "1 ms was asked for, the interval of 5 s applies");
        assertEquals("working", service.get(id, ALICE).getString("status"));
    }

    @Test
    void anInstanceThatIsAskingHoldsTheClaimSoTheOthersLeaveTheTaskAlone() {
        TaskService first  = service(job);
        TaskService second = service(job);
        id(first.createExternal(ToolResult.externalTask(handle("shared")), call(ALICE)));
        clock.advance(5_000);
        AtomicInteger inner = new AtomicInteger();
        job.during = () -> {
            second.pollExternal();
            inner.incrementAndGet();
        };
        job.script.add(ExternalJobStatus.running());
        first.pollExternal();
        assertEquals(1, inner.get());
        assertEquals(1, job.polls.get(), "the second instance saw the claim and did not ask");
    }

    @Test
    void aClaimThatRunsOutBecauseTheInstanceDiedLetsAnotherInstanceAsk() {
        TaskService dead  = service(job);
        TaskService alive = service(job);
        id(dead.createExternal(ToolResult.externalTask(handle("orphan")), call(ALICE)));
        clock.advance(5_000);
        // the dead instance claimed the poll and never finished it
        String id = store.pollDue(clock.instant(), 10).get(0);
        TaskRecord rec = store.find(id).orElseThrow();
        assertTrue(store.replace(rec, rec.edit(clock.instant(), false, edit -> edit.nextPollAt(clock.instant().plusMillis(30_000)))));
        alive.pollExternal();
        assertEquals(0, job.polls.get());
        clock.advance(30_000);
        job.script.add(ExternalJobStatus.completed(ToolResult.text("done anyway")));
        alive.pollExternal();
        assertEquals("completed", alive.get(id, ALICE).getString("status"));
    }

    @Test
    void failedPollsAreRepeatedAndTheTaskFailsAfterTooManyInARow() {
        TaskService service = service(job);
        String id = id(service.createExternal(ToolResult.externalTask(handle("flaky")), call(ALICE)));
        job.script.add(new IllegalStateException("connection refused"));
        job.script.add(new IllegalStateException("connection refused"));
        job.script.add(ExternalJobStatus.running("back"));
        job.script.add(new IllegalStateException("again 1"));
        job.script.add(new IllegalStateException("again 2"));
        job.script.add(new IllegalStateException("again 3"));
        for (int i = 0; i < 3; i++) {
            clock.advance(5_000);
            service.pollExternal();
            assertEquals("working", service.get(id, ALICE).getString("status"), "failure " + (i + 1) + " of 3 is not the end");
        }
        assertEquals(0, store.find(id).orElseThrow().pollFailures(), "a good answer starts the count again");
        clock.advance(5_000);
        service.pollExternal();
        clock.advance(5_000);
        service.pollExternal();
        assertEquals("working", service.get(id, ALICE).getString("status"));
        clock.advance(5_000);
        service.pollExternal();
        JsonObject failed = service.get(id, ALICE);
        assertEquals("failed", failed.getString("status"));
        assertEquals("The external job could not be reached", failed.getJsonObject("error").getString("message"));
        assertFalse(failed.toString().contains("again"), "the cause stays in the log");
    }

    @Test
    void aFailedStatusEndsTheTaskWithTheMessage() {
        TaskService service = service(job);
        String id = id(service.createExternal(ToolResult.externalTask(handle("bad")), call(ALICE)));
        job.script.add(ExternalJobStatus.failed("out of memory on node 7"));
        clock.advance(5_000);
        service.pollExternal();
        JsonObject failed = service.get(id, ALICE);
        assertEquals("failed", failed.getString("status"));
        assertEquals(ErrorCodes.INTERNAL_ERROR, failed.getJsonObject("error").getInt("code"));
        assertEquals("out of memory on node 7", failed.getJsonObject("error").getString("message"));
    }

    @Test
    void aResultThatIsTooLargeFailsTheTask() {
        TaskService service = new TaskService(store, new TaskConfig(true, 3_600_000L, 2_000L, 1024, 100, null, 10_000L, 30_000L, 30_000L, 20L, 60_000L, 2_000L, 3, 5_000L, 3), McpRegistry.of(List.of()), clock, Runnable::run, List.of(job));
        String id = id(service.createExternal(ToolResult.externalTask(handle("big")), call(ALICE)));
        job.script.add(ExternalJobStatus.completed(ToolResult.text("x".repeat(5_000))));
        clock.advance(5_000);
        service.pollExternal();
        assertEquals("failed", service.get(id, ALICE).getString("status"));
    }

    @Test
    void theStartGetsTheTaskIdAndAnIdReachedByCallbackEndsTheTaskAtOnce() {
        TaskService service = service(job);
        McpExternalJobs jobs = new McpExternalJobs(service);
        AtomicReference<String> given = new AtomicReference<>();
        JsonObject created = service.createExternal(ToolResult.externalTask(taskId -> {
            given.set(taskId);
            return handle("hooked");
        }), call(ALICE));
        String id = id(created);
        assertEquals(id, given.get(), "the other system can be told the id before the task is stored");

        assertTrue(jobs.progress(id, "halfway"));
        assertFalse(jobs.progress(id, "halfway"), "nothing changed");
        assertEquals("halfway", service.get(id, ALICE).getString("statusMessage"));
        assertTrue(jobs.complete(id, ToolResult.text("pushed")));
        assertEquals("completed", service.get(id, ALICE).getString("status"));
        assertFalse(jobs.complete(id, ToolResult.text("twice")), "already ended");
        assertFalse(jobs.fail(id, "late"));
        assertEquals("pushed", service.get(id, ALICE).getJsonObject("result").getJsonArray("content").getJsonObject(0).getString("text"));
        assertFalse(jobs.complete("unknown", ToolResult.text("x")));
        assertFalse(jobs.complete(null, ToolResult.text("x")));
        assertFalse(jobs.complete("x".repeat(500), ToolResult.text("x")));

        String other = id(service.createExternal(ToolResult.externalTask(handle("hooked2")), call(ALICE)));
        assertTrue(jobs.fail(other, "the build broke"));
        JsonObject failed = service.get(other, ALICE);
        assertEquals("failed", failed.getString("status"));
        assertEquals("the build broke", failed.getJsonObject("error").getString("message"));
    }

    @Test
    void aCallbackReachesATaskThatAnotherInstanceCreated() {
        TaskService first  = service(job);
        TaskService second = service(job);
        String id = id(first.createExternal(ToolResult.externalTask(handle("elsewhere")), call(ALICE)));
        assertTrue(new McpExternalJobs(second).complete(id, ToolResult.text("from the other node")));
        assertEquals("completed", first.get(id, ALICE).getString("status"));
    }

    @Test
    void aCallbackCannotTouchAnOrdinaryTask() throws Exception {
        TaskService service = service(job);
        String id = id(service.create(ToolResult.task(ctx -> ToolResult.text("x")), call(ALICE)));
        assertFalse(new McpExternalJobs(service).complete(id, ToolResult.text("forged")));
        assertFalse(new McpExternalJobs(service).fail(id, "forged"));
    }

    @Test
    void cancelStopsTheJobAndEndsTheTaskForItsOwnerOnly() {
        TaskService service = service(job);
        String id = id(service.createExternal(ToolResult.externalTask(handle("cancel-me")), call(ALICE)));
        assertThrows(McpProtocolException.class, () -> service.cancel(id, BOB));
        assertEquals(0, job.cancels.size());

        service.cancel(id, ALICE);
        assertEquals(List.of("cancel-me"), job.cancels);
        assertEquals("cancelled", service.get(id, ALICE).getString("status"));
        assertNull(store.find(id).orElseThrow().handle());
        service.cancel(id, ALICE);
        assertEquals(1, job.cancels.size(), "a task that ended is not cancelled again");
    }

    @Test
    void aCancelThatFailsIsRepeatedWithTheNextPollsAndTheTaskFailsAfterTooManyFailures() {
        TaskService service = service(job);
        String id = id(service.createExternal(ToolResult.externalTask(handle("stuck")), call(ALICE)));
        job.cancelFails = true;
        service.cancel(id, ALICE);
        assertEquals("working", service.get(id, ALICE).getString("status"));
        assertTrue(store.find(id).orElseThrow().cancelRequested());
        assertEquals(1, job.cancels.size());

        job.cancelFails = false;
        clock.advance(5_000);
        service.pollExternal();
        assertEquals("cancelled", service.get(id, ALICE).getString("status"));
        assertEquals(2, job.cancels.size());
        assertEquals(0, job.polls.get(), "a task with a cancellation pending is not asked about, the job is stopped");

        String hopeless = id(service.createExternal(ToolResult.externalTask(handle("hopeless")), call(ALICE)));
        job.cancelFails = true;
        service.cancel(hopeless, ALICE);
        for (int i = 0; i < 2; i++) {
            clock.advance(5_000);
            service.pollExternal();
        }
        assertEquals("failed", service.get(hopeless, ALICE).getString("status"));
    }

    @Test
    void aPollThatFinishesAfterACancellationDoesNotOverwriteIt() {
        TaskService service = service(job);
        String id = id(service.createExternal(ToolResult.externalTask(handle("race")), call(ALICE)));
        job.script.add(ExternalJobStatus.completed(ToolResult.text("late result")));
        job.during = () -> service.cancel(id, ALICE);
        clock.advance(5_000);
        service.pollExternal();
        assertEquals("cancelled", service.get(id, ALICE).getString("status"));
    }

    @Test
    void aToolWithoutAFollowerOrWithABadHandleCreatesNoTaskAndStartsNoJobOrStopsIt() {
        TaskService nobody = new TaskService(store, config(), McpRegistry.of(List.of()), clock, Runnable::run, List.of());
        AtomicInteger started = new AtomicInteger();
        assertThrows(McpProtocolException.class, () -> nobody.createExternal(ToolResult.externalTask(taskId -> {
            started.incrementAndGet();
            return handle("x");
        }), call(ALICE)));
        assertEquals(0, started.get(), "no follower: nothing is started that nobody would follow");

        TaskService service = service(job);
        assertThrows(McpProtocolException.class, () -> service.createExternal(ToolResult.externalTask(taskId -> Json.createObjectBuilder().add("job", "too-big").add("blob", "x".repeat(20_000)).build()), call(ALICE)));
        assertEquals(List.of("too-big"), job.cancels, "the job that cannot be followed is stopped");
        assertThrows(McpProtocolException.class, () -> service.createExternal(ToolResult.externalTask(taskId -> {
            throw new IllegalStateException("scheduler down");
        }), call(ALICE)));
        McpProtocolException refused = assertThrows(McpProtocolException.class, () -> service.createExternal(ToolResult.externalTask(taskId -> {
            throw new McpProtocolException(200, ErrorCodes.INVALID_PARAMS, "no such pipeline", null);
        }), call(ALICE)));
        assertEquals(ErrorCodes.INVALID_PARAMS, refused.code());
        assertEquals(0, store.size());
    }

    @Test
    void twoFollowersForOneToolFailTheValidation() {
        TaskService service = new TaskService(store, config(), McpRegistry.of(List.of()), clock, Runnable::run, List.of(new ScriptedJob(), new ScriptedJob()));
        IllegalStateException e = assertThrows(IllegalStateException.class, service::validateExternalJobs);
        assertTrue(e.getMessage().contains("'build'"));
    }

    @Test
    void theHandleNeverReachesTheClient() {
        TaskService service = service(job);
        JsonObject created = service.createExternal(ToolResult.externalTask(handle("secret-token-123")), call(ALICE));
        String id = id(created);
        assertFalse(created.toString().contains("secret-token-123"));
        assertFalse(service.get(id, ALICE).toString().contains("secret-token-123"));
        assertFalse(service.view(id, ALICE).orElseThrow().params().toString().contains("secret-token-123"));
    }

    @Test
    void onTheWireAClientWithTheExtensionGetsATaskAndOthersAreRefusedBeforeAnythingStarts() {
        AtomicInteger started = new AtomicInteger();
        McpRequestHandler on = handler(true, started);
        JsonObject created = result(send(on, "tools/call", "build", Json.createObjectBuilder().add("name", "build"), declared()));
        assertEquals("task", created.getString("resultType"));
        String id = created.getString("taskId");
        assertEquals(1, started.get());

        job.script.add(ExternalJobStatus.completed(ToolResult.text("green")));
        clock.advance(5_000);
        servicesOf.get(0).pollExternal();
        JsonObject polled = result(send(on, "tasks/get", id, Json.createObjectBuilder().add("taskId", id), declared()));
        assertEquals("completed", polled.getString("status"));
        assertEquals("green", polled.getJsonObject("result").getJsonArray("content").getJsonObject(0).getString("text"));

        McpHttpResponse withoutExtension = send(on, "tools/call", "build", Json.createObjectBuilder().add("name", "build"), null);
        assertEquals(400, withoutExtension.status());
        assertEquals(ErrorCodes.MISSING_REQUIRED_CLIENT_CAPABILITY, json(withoutExtension).getJsonObject("error").getInt("code"));
        McpHttpResponse switchedOff = send(handler(false, started), "tools/call", "build", Json.createObjectBuilder().add("name", "build"), declared());
        assertEquals(ErrorCodes.MISSING_REQUIRED_CLIENT_CAPABILITY, json(switchedOff).getJsonObject("error").getInt("code"));
        assertEquals(1, started.get(), "the refused calls started no job");
    }


    // ---- helpers ----

    private final List<TaskService> servicesOf = new ArrayList<>();

    private TaskConfig config() {
        return new TaskConfig(true, 3_600_000L, 2_000L, 1024 * 1024, 100, null, 10_000L, 30_000L, 30_000L, 20L, 60_000L, 2_000L, 3, 5_000L, 3);
    }

    private TaskService service(ExternalJob follower) {
        return new TaskService(store, config(), McpRegistry.of(List.of()), clock, Runnable::run, List.of(follower));
    }

    private McpRequestHandler handler(boolean tasksOn, AtomicInteger started) {
        McpRegistry registry = McpRegistry.of(List.of(new BuildTool(started)));
        TaskConfig  taskConfig = new TaskConfig(tasksOn, 3_600_000L, 2_000L, 1024 * 1024, 100, null, 10_000L, 30_000L, 30_000L, 20L, 60_000L, 2_000L, 3, 5_000L, 3);
        TaskService service = new TaskService(store, taskConfig, registry, clock, Runnable::run, List.of(job));
        servicesOf.add(service);
        McpConfig config = new McpConfig(null, 65536, 1000L, "t", "1", null);
        return new McpRequestHandler(registry, config, AuthConfig.disabled(), header -> Caller.ANONYMOUS, new McpNotifications(config), McpExtensions.none(), service);
    }

    private static JsonObject handle(String job) {
        return Json.createObjectBuilder().add("job", job).build();
    }

    private static ToolCall call(Caller caller) {
        return new ToolCall("build", JsonObject.EMPTY_JSON_OBJECT, JsonObject.EMPTY_JSON_OBJECT, null, null, null, caller);
    }

    private static String id(JsonObject created) {
        return created.getString("taskId");
    }

    private static JsonObject declared() {
        return Json.createObjectBuilder().add("extensions", Json.createObjectBuilder().add(TASKS, Json.createObjectBuilder())).build();
    }

    private static McpHttpResponse send(McpRequestHandler target, String method, String name, JsonObjectBuilder params, JsonObject clientCapabilities) {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.put("MCP-Protocol-Version", "2026-07-28");
        headers.put("Mcp-Method", method);
        if (name != null) {
            headers.put("Mcp-Name", name);
        }
        JsonObjectBuilder all = params == null ? Json.createObjectBuilder() : params;
        JsonObject meta = Json.createObjectBuilder().add("io.modelcontextprotocol/protocolVersion", "2026-07-28").add("io.modelcontextprotocol/clientCapabilities", clientCapabilities == null ? Json.createObjectBuilder().build() : clientCapabilities).build();
        String body = Json.createObjectBuilder().add("jsonrpc", "2.0").add("id", 1).add("method", method).add("params", all.add("_meta", meta)).build().toString();
        return target.handle(headers::get, new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static JsonObject json(McpHttpResponse response) {
        return Json.createReader(new StringReader(response.body())).readObject();
    }

    private static JsonObject result(McpHttpResponse response) {
        assertEquals(200, response.status(), response.body());
        return json(response).getJsonObject("result");
    }


    /** Answers polls from a script: an {@link ExternalJobStatus} is returned, a {@link RuntimeException} is thrown; an empty script says "running". */
    private static final class ScriptedJob implements ExternalJob {

        final Deque<Object>  script       = new ArrayDeque<>();
        final AtomicInteger  polls        = new AtomicInteger();
        final List<String>   handlesSeen  = new ArrayList<>();
        final List<String>   cancels      = new ArrayList<>();
        volatile boolean     cancelFails;
        volatile Runnable    during;

        @Override
        public String tool() {
            return "build";
        }

        @Override
        public ExternalJobStatus poll(String taskId, JsonObject handle) {
            polls.incrementAndGet();
            handlesSeen.add(handle.getString("job"));
            Runnable inside = during;
            if (inside != null) {
                during = null;
                inside.run();
            }
            Object next = script.poll();
            if (next instanceof RuntimeException e) {
                throw e;
            }
            return next instanceof ExternalJobStatus status ? status : ExternalJobStatus.running();
        }

        @Override
        public void cancel(String taskId, JsonObject handle) {
            cancels.add(handle.getString("job"));
            if (cancelFails) {
                throw new IllegalStateException("cannot cancel");
            }
        }
    }


    private static final class BuildTool implements McpToolHandler {

        private final AtomicInteger started;

        private BuildTool(AtomicInteger started) {
            this.started = started;
        }

        @Override
        public String name() {
            return "build";
        }

        @Override
        public String description() {
            return "starts a build somewhere else";
        }

        @Override
        public JsonObject inputSchema() {
            return Json.createObjectBuilder().add("type", "object").build();
        }

        @Override
        public ToolResult call(ToolCall call) {
            return ToolResult.externalTask(taskId -> {
                started.incrementAndGet();
                return handle("wire-build");
            });
        }
    }


    private static final class TestClock extends Clock {

        private volatile Instant now = Instant.parse("2026-11-09T12:00:00Z");


        void advance(long millis) {
            now = now.plusMillis(millis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
