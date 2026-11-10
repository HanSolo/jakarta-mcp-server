package eu.hansolo.mcp.server;

import eu.hansolo.mcp.api.Caller;
import eu.hansolo.mcp.api.Elicitation;
import eu.hansolo.mcp.api.InputRequests;
import eu.hansolo.mcp.api.RequestCancelledException;
import eu.hansolo.mcp.api.TaskWork;
import eu.hansolo.mcp.api.ToolCall;
import eu.hansolo.mcp.api.ToolResult;
import eu.hansolo.mcp.protocol.ErrorCodes;
import eu.hansolo.mcp.protocol.McpProtocolException;
import eu.hansolo.mcp.tasks.InMemoryTaskStore;
import eu.hansolo.mcp.tasks.TaskRecord;
import eu.hansolo.mcp.tasks.TaskStore;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The task engine: states, ownership, input, cancellation, leases, expiry. */
class TaskServiceTest {

    private static final Caller ALICE = new Caller("alice", Set.of(), JsonObject.EMPTY_JSON_OBJECT);
    private static final Caller BOB   = new Caller("bob", Set.of(), JsonObject.EMPTY_JSON_OBJECT);

    private final TestClock         clock = new TestClock();
    private final InMemoryTaskStore store = new InMemoryTaskStore();


    @Test
    void aTaskIsStoredBeforeTheClientHearsOfItAndCompletesWithTheResult() throws Exception {
        TaskService service = service(config(100));
        CountDownLatch release = new CountDownLatch(1);
        JsonObject created = service.create(ToolResult.task(ctx -> {
            release.await(5, TimeUnit.SECONDS);
            return ToolResult.text("done");
        }), call(ALICE));
        assertEquals("task", created.getString("resultType"));
        assertEquals("working", created.getString("status"));
        assertEquals(43, created.getString("taskId").length());
        assertEquals(3_600_000L, created.getJsonNumber("ttlMs").longValue());
        assertEquals(2_000L, created.getJsonNumber("pollIntervalMs").longValue());
        String id = created.getString("taskId");
        assertEquals("working", service.get(id, ALICE).getString("status"));
        release.countDown();
        JsonObject done = awaitStatus(service, id, ALICE, "completed");
        assertEquals("complete", done.getString("resultType"));
        assertEquals("done", done.getJsonObject("result").getJsonArray("content").getJsonObject(0).getString("text"));
        assertFalse(done.getJsonObject("result").getBoolean("isError"));
        service.close();
    }

    @Test
    void aProtocolErrorFailsTheTaskAndAnyOtherExceptionIsAToolError() throws Exception {
        TaskService service = service(config(100));
        String failing = id(service.create(ToolResult.task(ctx -> {
            throw new McpProtocolException(200, ErrorCodes.INVALID_PARAMS, "bad input", null);
        }), call(ALICE)));
        JsonObject failed = awaitStatus(service, failing, ALICE, "failed");
        assertEquals(ErrorCodes.INVALID_PARAMS, failed.getJsonObject("error").getInt("code"));
        assertEquals("bad input", failed.getJsonObject("error").getString("message"));
        assertFalse(failed.containsKey("result"));

        String crashing = id(service.create(ToolResult.task(ctx -> {
            throw new IllegalStateException("secret detail");
        }), call(ALICE)));
        JsonObject completed = awaitStatus(service, crashing, ALICE, "completed");
        assertTrue(completed.getJsonObject("result").getBoolean("isError"));
        assertFalse(completed.toString().contains("secret detail"));
        service.close();
    }

    @Test
    void onlyTheOwnerSeesATaskAndEveryMissIsTheSameError() throws Exception {
        TaskService service = service(config(100));
        String id = id(service.create(ToolResult.task(ctx -> ToolResult.text("x")), call(ALICE)));
        McpProtocolException foreign = assertThrows(McpProtocolException.class, () -> service.get(id, BOB));
        McpProtocolException unknown = assertThrows(McpProtocolException.class, () -> service.get("nope", ALICE));
        McpProtocolException huge = assertThrows(McpProtocolException.class, () -> service.get("x".repeat(500), ALICE));
        assertEquals(ErrorCodes.INVALID_PARAMS, foreign.code());
        assertEquals(foreign.getMessage(), unknown.getMessage());
        assertEquals(foreign.getMessage(), huge.getMessage());
        assertThrows(McpProtocolException.class, () -> service.cancel(id, BOB));
        assertThrows(McpProtocolException.class, () -> service.update(id, BOB, JsonObject.EMPTY_JSON_OBJECT));
        service.close();
    }

    @Test
    void theStatusMessageIsVisibleWhilePolling() throws Exception {
        TaskService service = service(config(100));
        CountDownLatch said = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        String id = id(service.create(ToolResult.task(ctx -> {
            ctx.status("fetching 1 of 3");
            said.countDown();
            release.await(5, TimeUnit.SECONDS);
            return ToolResult.text("ok");
        }), call(ALICE)));
        assertTrue(said.await(5, TimeUnit.SECONDS));
        assertEquals("fetching 1 of 3", service.get(id, ALICE).getString("statusMessage"));
        release.countDown();
        awaitStatus(service, id, ALICE, "completed");
        service.close();
    }

    @Test
    void workThatAsksWaitsForTasksUpdateAndIgnoresUnknownAndRepeatedKeys() throws Exception {
        TaskService service = service(config(100));
        AtomicReference<JsonObject> answers = new AtomicReference<>();
        AtomicReference<Exception> reuse = new AtomicReference<>();
        String id = id(service.create(ToolResult.task(ctx -> {
            answers.set(ctx.ask(InputRequests.of("confirm", Elicitation.form("Proceed?").string("why", p -> p.required()))));
            try {
                ctx.ask(InputRequests.of("confirm", Elicitation.form("Again?").string("why", p -> p.required())));
            } catch (IllegalArgumentException e) {
                reuse.set(e);
            }
            return ToolResult.text("went on");
        }), call(ALICE)));
        JsonObject waiting = awaitStatus(service, id, ALICE, "input_required");
        assertTrue(waiting.getJsonObject("inputRequests").containsKey("confirm"));
        service.update(id, ALICE, Json.createObjectBuilder().add("other", Json.createObjectBuilder().add("action", "accept")).add("confirm", Json.createObjectBuilder().add("action", "accept").add("content", Json.createObjectBuilder().add("why", "because"))).build());
        awaitStatus(service, id, ALICE, "completed");
        assertEquals("because", answers.get().getJsonObject("confirm").getJsonObject("content").getString("why"));
        assertFalse(answers.get().containsKey("other"));
        assertNotNull(reuse.get());
        // answering again, after the task ended, is accepted and changes nothing
        service.update(id, ALICE, Json.createObjectBuilder().add("confirm", Json.createObjectBuilder().add("action", "decline")).build());
        assertEquals("went on", service.get(id, ALICE).getJsonObject("result").getJsonArray("content").getJsonObject(0).getString("text"));
        service.close();
    }

    @Test
    void cancellingAskedWorkEndsItAsCancelled() throws Exception {
        TaskService service = service(config(100));
        String id = id(service.create(ToolResult.task(ctx -> {
            ctx.ask(InputRequests.of("k", Elicitation.form("?").string("a", p -> p.required())));
            return ToolResult.text("never");
        }), call(ALICE)));
        awaitStatus(service, id, ALICE, "input_required");
        service.cancel(id, ALICE);
        awaitStatus(service, id, ALICE, "cancelled");
        service.cancel(id, ALICE);
        assertEquals("cancelled", service.get(id, ALICE).getString("status"));
        service.close();
    }

    @Test
    void cancellationIsCooperativeSoWorkThatIgnoresItStillCompletes() throws Exception {
        TaskService service = service(config(100));
        CountDownLatch release = new CountDownLatch(1);
        String stubborn = id(service.create(ToolResult.task(ctx -> {
            release.await(5, TimeUnit.SECONDS);
            return ToolResult.text("finished anyway");
        }), call(ALICE)));
        service.cancel(stubborn, ALICE);
        assertEquals("working", service.get(stubborn, ALICE).getString("status"));
        release.countDown();
        awaitStatus(service, stubborn, ALICE, "completed");

        String polite = id(service.create(ToolResult.task(ctx -> {
            while (!ctx.isCancelled()) {
                Thread.sleep(5);
            }
            throw new RequestCancelledException("stop", null);
        }), call(ALICE)));
        service.cancel(polite, ALICE);
        awaitStatus(service, polite, ALICE, "cancelled");
        service.close();
    }

    @Test
    void aTaskWhoseWorkerStoppedRenewingItsLeaseIsDeclaredFailedAndStaysFailed() throws Exception {
        TaskService service = service(config(100));
        CountDownLatch release = new CountDownLatch(1);
        String id = id(service.create(ToolResult.task(ctx -> {
            release.await(5, TimeUnit.SECONDS);
            return ToolResult.text("too late");
        }), call(ALICE)));
        clock.advance(31_000);
        service.sweep();
        JsonObject lost = service.get(id, ALICE);
        assertEquals("failed", lost.getString("status"));
        assertEquals(ErrorCodes.INTERNAL_ERROR, lost.getJsonObject("error").getInt("code"));
        release.countDown();
        Thread.sleep(150);
        assertEquals("failed", service.get(id, ALICE).getString("status"));
        service.close();
    }

    @Test
    void anInstanceThatNeverCreatedATaskStillSweepsTheTasksOfADeadOne() throws Exception {
        TaskService a = service(config(100));
        CountDownLatch release = new CountDownLatch(1);
        String id = id(a.create(ToolResult.task(ctx -> {
            release.await(5, TimeUnit.SECONDS);
            return ToolResult.text("x");
        }), call(ALICE)));
        TaskService b = new TaskService(store, new TaskConfig(true, 3_600_000L, 2_000L, 1024 * 1024, 100, null, 10_000L, 30_000L, 50L, 100L, 60_000L), McpRegistry.of(List.of()), clock, Executors.newVirtualThreadPerTaskExecutor());
        clock.advance(31_000);
        b.start();
        long deadline = System.currentTimeMillis() + 5_000;
        String status = "working";
        while (System.currentTimeMillis() < deadline && !"failed".equals(status)) {
            Thread.sleep(25);
            status = store.find(id).orElseThrow().status().wire();
        }
        assertEquals("failed", status);
        release.countDown();
        b.close();
        a.close();
    }

    @Test
    void aLostTaskWithAPendingCancellationEndsAsCancelled() throws Exception {
        TaskService service = service(config(100));
        CountDownLatch release = new CountDownLatch(1);
        String id = id(service.create(ToolResult.task(ctx -> {
            release.await(5, TimeUnit.SECONDS);
            return ToolResult.text("x");
        }), call(ALICE)));
        service.cancel(id, ALICE);
        clock.advance(31_000);
        service.sweep();
        assertEquals("cancelled", service.get(id, ALICE).getString("status"));
        release.countDown();
        service.close();
    }

    @Test
    void aHeartbeatKeepsTheLeaseAlive() throws Exception {
        TaskService service = service(config(100));
        CountDownLatch release = new CountDownLatch(1);
        String id = id(service.create(ToolResult.task(ctx -> {
            release.await(5, TimeUnit.SECONDS);
            return ToolResult.text("x");
        }), call(ALICE)));
        clock.advance(25_000);
        service.heartbeat();
        clock.advance(25_000);
        service.sweep();
        assertEquals("working", service.get(id, ALICE).getString("status"));
        release.countDown();
        awaitStatus(service, id, ALICE, "completed");
        service.close();
    }

    @Test
    void anExpiredTaskIsGoneAndTheSweepDropsItsRecord() throws Exception {
        TaskService service = service(config(100));
        String id = id(service.create(ToolResult.task(ctx -> ToolResult.text("x")).withTtlMs(1_000), call(ALICE)));
        awaitStatus(service, id, ALICE, "completed");
        assertEquals(1, store.size());
        clock.advance(1_001);
        assertThrows(McpProtocolException.class, () -> service.get(id, ALICE));
        service.sweep();
        assertEquals(0, store.size());
        service.close();
    }

    @Test
    void aTaskWithoutExpiryReportsANullTtl() throws Exception {
        TaskService service = service(config(100));
        JsonObject created = service.create(ToolResult.task(ctx -> ToolResult.text("x")).withoutExpiry().withPollIntervalMs(500), call(ALICE));
        assertEquals(JsonValue.NULL, created.get("ttlMs"));
        assertEquals(500, created.getInt("pollIntervalMs"));
        String id = id(created);
        awaitStatus(service, id, ALICE, "completed");
        clock.advance(10L * 365 * 24 * 3600 * 1000);
        service.sweep();
        assertEquals("completed", service.get(id, ALICE).getString("status"));
        service.close();
    }

    @Test
    void aResultThatIsTooBigFailsTheTask() throws Exception {
        TaskService service = service(new TaskConfig(true, 3_600_000L, 2_000L, 2048, 100, null, 10_000L, 30_000L, 30_000L, 20L, 60_000L));
        String id = id(service.create(ToolResult.task(ctx -> ToolResult.text("x".repeat(5000))), call(ALICE)));
        JsonObject failed = awaitStatus(service, id, ALICE, "failed");
        assertTrue(failed.getJsonObject("error").getString("message").contains("max-result-bytes"));
        service.close();
    }

    @Test
    void anInstanceRunsOnlyAsManyTasksAsConfigured() throws Exception {
        TaskService service = service(new TaskConfig(true, 3_600_000L, 2_000L, 1024 * 1024, 1, null, 10_000L, 30_000L, 30_000L, 20L, 60_000L));
        CountDownLatch release = new CountDownLatch(1);
        String first = id(service.create(ToolResult.task(ctx -> {
            release.await(5, TimeUnit.SECONDS);
            return ToolResult.text("x");
        }), call(ALICE)));
        McpProtocolException full = assertThrows(McpProtocolException.class, () -> service.create(ToolResult.task(ctx -> ToolResult.text("y")), call(ALICE)));
        assertEquals(503, full.httpStatus());
        release.countDown();
        awaitStatus(service, first, ALICE, "completed");
        Thread.sleep(50);
        service.create(ToolResult.task(ctx -> ToolResult.text("z")), call(ALICE));
        service.close();
    }

    @Test
    void workThatEndsWithAnythingButAResultFailsTheTask() throws Exception {
        TaskService service = service(config(100));
        String id = id(service.create(ToolResult.task(ctx -> ToolResult.task(c -> ToolResult.text("nested"))), call(ALICE)));
        awaitStatus(service, id, ALICE, "failed");
        service.close();
    }

    @Test
    void shuttingDownEndsRunningTasksAsFailedAtOnce() throws Exception {
        TaskService service = service(config(100));
        CountDownLatch release = new CountDownLatch(1);
        String id = id(service.create(ToolResult.task(ctx -> {
            release.await(5, TimeUnit.SECONDS);
            return ToolResult.text("x");
        }), call(ALICE)));
        service.close();
        TaskService other = new TaskService(store, config(100), McpRegistry.of(List.of()), clock, Runnable::run);
        assertEquals("failed", other.get(id, ALICE).getString("status"));
        release.countDown();
    }

    @Test
    void inlineRunReturnsTheResultAndCannotAskForInput() throws Exception {
        TaskService service = service(config(100));
        AtomicReference<String> seen = new AtomicReference<>();
        ToolResult result = service.runInline(ToolResult.task(ctx -> {
            seen.set(ctx.taskId());
            ctx.status("ignored");
            return ToolResult.text("inline");
        }), call(ALICE));
        assertEquals("", seen.get());
        assertFalse(((ToolResult.Complete) result).isError());
        McpProtocolException refused = assertThrows(McpProtocolException.class, () -> service.runInline(ToolResult.task(ctx -> {
            ctx.ask(InputRequests.of("k", Elicitation.form("?").string("a", p -> p.required())));
            return ToolResult.text("never");
        }), call(ALICE)));
        assertEquals(ErrorCodes.MISSING_REQUIRED_CLIENT_CAPABILITY, refused.code());
        service.close();
    }

    @Test
    void aTaskNeedsWork() {
        assertThrows(IllegalArgumentException.class, () -> ToolResult.task((TaskWork) null));
        assertThrows(IllegalArgumentException.class, () -> ToolResult.task(ctx -> null).withTtlMs(0));
    }


    @Test
    void theServerUsesTheOneStoreThatIsAvailableAndTheMemoryStoreIfThereIsNone() {
        TaskStoreStub off = new TaskStoreStub(false);
        TaskStoreStub on  = new TaskStoreStub(true);
        TaskConfig    cfg = config(100);
        McpRegistry   reg = McpRegistry.of(List.of());
        assertTrue(new TaskService(instances(), cfg, reg).usesMemoryStore());
        assertTrue(new TaskService(instances(off), cfg, reg).usesMemoryStore());
        assertFalse(new TaskService(instances(off, on), cfg, reg).usesMemoryStore());
        assertThrows(IllegalStateException.class, () -> new TaskService(instances(on, new TaskStoreStub(true)), cfg, reg));
        assertTrue(new TaskService(instances(on, new TaskStoreStub(true)), new TaskConfig(false, 1000L, 1000L, 1024, 1, null, 100L, 300L, 100L, 100L, 1000L), reg).usesMemoryStore());
    }

    @SuppressWarnings("unchecked")
    private static jakarta.enterprise.inject.Instance<eu.hansolo.mcp.tasks.TaskStore> instances(eu.hansolo.mcp.tasks.TaskStore... stores) {
        return (jakarta.enterprise.inject.Instance<eu.hansolo.mcp.tasks.TaskStore>) java.lang.reflect.Proxy.newProxyInstance(TaskServiceTest.class.getClassLoader(), new Class<?>[] { jakarta.enterprise.inject.Instance.class }, (proxy, method, args) -> {
            if (method.getName().equals("iterator")) {
                return List.of(stores).iterator();
            }
            throw new UnsupportedOperationException(method.getName());
        });
    }

    private static final class TaskStoreStub implements eu.hansolo.mcp.tasks.TaskStore {

        private final InMemoryTaskStore inner = new InMemoryTaskStore();
        private final boolean           available;


        private TaskStoreStub(boolean available) {
            this.available = available;
        }


        @Override
        public boolean available() {
            return available;
        }

        @Override
        public void create(eu.hansolo.mcp.tasks.TaskRecord task) {
            inner.create(task);
        }

        @Override
        public java.util.Optional<eu.hansolo.mcp.tasks.TaskRecord> find(String id) {
            return inner.find(id);
        }

        @Override
        public boolean replace(eu.hansolo.mcp.tasks.TaskRecord current, eu.hansolo.mcp.tasks.TaskRecord next) {
            return inner.replace(current, next);
        }

        @Override
        public List<String> leaseExpired(Instant now, int limit) {
            return inner.leaseExpired(now, limit);
        }

        @Override
        public List<String> expired(Instant now, int limit) {
            return inner.expired(now, limit);
        }

        @Override
        public void delete(String id) {
            inner.delete(id);
        }
    }

    @Test
    void viewsLooksUpAllTasksInOneStoreCallAndLeavesOutWhatTheCallerMayNotSee() throws Exception {
        AtomicInteger finds    = new AtomicInteger();
        AtomicInteger findAlls = new AtomicInteger();
        TaskStore counting = new TaskStore() {
            @Override public void create(TaskRecord task) { store.create(task); }
            @Override public Optional<TaskRecord> find(String id) { finds.incrementAndGet(); return store.find(id); }
            @Override public Map<String, TaskRecord> findAll(Collection<String> ids) { findAlls.incrementAndGet(); return TaskStore.super.findAll(ids); }
            @Override public boolean replace(TaskRecord current, TaskRecord next) { return store.replace(current, next); }
            @Override public List<String> leaseExpired(Instant now, int limit) { return store.leaseExpired(now, limit); }
            @Override public List<String> expired(Instant now, int limit) { return store.expired(now, limit); }
            @Override public void delete(String id) { store.delete(id); }
        };
        TaskService service = new TaskService(counting, config(100), McpRegistry.of(List.of()), clock, Executors.newVirtualThreadPerTaskExecutor());
        CountDownLatch release = new CountDownLatch(1);
        TaskWork work = ctx -> {
            release.await(5, TimeUnit.SECONDS);
            return ToolResult.text("done");
        };
        String first   = id(service.create(ToolResult.task(work), call(ALICE)));
        String second  = id(service.create(ToolResult.task(work), call(ALICE)));
        String foreign = id(service.create(ToolResult.task(work), call(BOB)));
        finds.set(0);

        Map<String, TaskService.TaskView> views = service.views(List.of(first, second, foreign, "unknown", first), ALICE);
        assertEquals(Set.of(first, second), views.keySet());
        assertEquals(first, views.get(first).params().getString("taskId"));
        assertEquals(1, findAlls.get());
        assertEquals(4, finds.get(), "the default findAll asks for each distinct id (a store without a batch query), but the service made one findAll call");
        assertEquals(List.of(), service.views(List.of(), ALICE).keySet().stream().toList());
        assertEquals(views.get(first).key(), service.view(first, ALICE).orElseThrow().key(), "same state, same key as the single lookup");
        release.countDown();
        service.close();
    }


    private TaskService service(TaskConfig config) {
        return new TaskService(store, config, McpRegistry.of(List.of()), clock, Executors.newVirtualThreadPerTaskExecutor());
    }

    private static TaskConfig config(long inputPollMs) {
        return new TaskConfig(true, 3_600_000L, 2_000L, 1024 * 1024, 100, null, 10_000L, 30_000L, 30_000L, inputPollMs, 60_000L);
    }

    private static ToolCall call(Caller caller) {
        return new ToolCall("work", JsonObject.EMPTY_JSON_OBJECT, JsonObject.EMPTY_JSON_OBJECT, null, null, null, caller);
    }

    private static String id(JsonObject created) {
        return created.getString("taskId");
    }

    private static JsonObject awaitStatus(TaskService service, String id, Caller caller, String status) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        JsonObject last = null;
        while (System.nanoTime() < deadline) {
            last = service.get(id, caller);
            if (last.getString("status").equals(status)) {
                return last;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Task never reached " + status + "; last seen: " + last);
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
