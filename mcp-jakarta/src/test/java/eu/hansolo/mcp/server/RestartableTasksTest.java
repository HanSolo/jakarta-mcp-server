package eu.hansolo.mcp.server;

import eu.hansolo.mcp.api.Caller;
import eu.hansolo.mcp.api.McpToolHandler;
import eu.hansolo.mcp.api.ToolCall;
import eu.hansolo.mcp.api.ToolResult;
import eu.hansolo.mcp.tasks.InMemoryTaskStore;
import eu.hansolo.mcp.tasks.TaskRecord;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A restartable task is run again by another instance when its worker is lost, up to {@code mcp.tasks.max-attempts} runs. */
class RestartableTasksTest {

    private static final Caller     ALICE = new Caller("alice", Set.of("tasks:run"), Json.createObjectBuilder().add("sub", "alice").build());
    private static final JsonObject TASKS = Json.createObjectBuilder().add("extensions", Json.createObjectBuilder().add("io.modelcontextprotocol/tasks", Json.createObjectBuilder())).build();

    private final TestClock         clock = new TestClock();
    private final InMemoryTaskStore store = new InMemoryTaskStore();
    private final AtomicInteger     runs  = new AtomicInteger();
    private final CountDownLatch    hold  = new CountDownLatch(1);


    /** The first run blocks until {@code hold} is released; every later run finishes at once. */
    private final McpToolHandler tool = new McpToolHandler() {
        @Override
        public String name() {
            return "job";
        }

        @Override
        public String description() {
            return "test";
        }

        @Override
        public JsonObject inputSchema() {
            return Json.createObjectBuilder().add("type", "object").build();
        }

        @Override
        public ToolResult call(ToolCall call) {
            return ToolResult.task(ctx -> {
                int run = runs.incrementAndGet();
                if (run == 1) {
                    hold.await(10, TimeUnit.SECONDS);
                    return ToolResult.text("first run");
                }
                return ToolResult.text("run " + run + " for " + call.caller().subject() + " with " + call.arguments().getString("n"));
            }).withRestartable();
        }
    };


    @Test
    void anotherInstanceRunsTheCallAgainWithTheStoredCallerAndArguments() throws Exception {
        TaskService a = service(3);
        TaskService b = service(3);
        String id = a.create(first(), call()).getString("taskId");
        assertTrue(store.find(id).orElseThrow().restartable());
        assertEquals(1, store.find(id).orElseThrow().attempt());

        clock.advance(31_000);
        b.sweep();
        TaskRecord done = awaitStatus(id, "completed");
        assertEquals(2, done.attempt());
        assertEquals("run 2 for alice with 7", done.result().getJsonArray("content").getJsonObject(0).getString("text"));

        hold.countDown();                       // the first worker comes back: it must not overwrite the second run
        Thread.sleep(300);
        assertEquals("run 2 for alice with 7", store.find(id).orElseThrow().result().getJsonArray("content").getJsonObject(0).getString("text"));
        b.close();
        a.close();
    }

    @Test
    void theAttemptLimitEndsTheTaskAsFailed() throws Exception {
        TaskService a = service(2);
        TaskService b = service(2);
        String id = a.create(first(), call()).getString("taskId");
        clock.advance(31_000);
        b.sweep();                              // attempt 2 starts and finishes at once
        awaitStatus(id, "completed");
        // a task that is lost on its last attempt fails: put one back to working with an expired lease at the limit
        TaskRecord done = store.find(id).orElseThrow();
        TaskRecord lost = done.edit(clock.instant(), edit -> edit.status(eu.hansolo.mcp.tasks.TaskStatus.WORKING).result(null).lease("gone", clock.instant().minusSeconds(1)));
        assertTrue(store.replace(done, lost));
        b.sweep();
        TaskRecord failed = store.find(id).orElseThrow();
        assertEquals("failed", failed.status().wire());
        assertEquals(2, failed.attempt());
        hold.countDown();
        b.close();
        a.close();
    }

    @Test
    void aTaskThatIsNotRestartableStaysFailed() throws Exception {
        TaskService a = service(3);
        TaskService b = service(3);
        String id = a.create(ToolResult.task(ctx -> {
            hold.await(10, TimeUnit.SECONDS);
            return ToolResult.text("x");
        }), call()).getString("taskId");
        assertFalse(store.find(id).orElseThrow().restartable());
        assertEquals(null, store.find(id).orElseThrow().call());
        clock.advance(31_000);
        b.sweep();
        assertEquals("failed", store.find(id).orElseThrow().status().wire());
        hold.countDown();
        b.close();
        a.close();
    }

    @Test
    void aMaximumOfOneAttemptSwitchesRestartsOff() throws Exception {
        TaskService a = service(1);
        String id = a.create(first(), call()).getString("taskId");
        assertFalse(store.find(id).orElseThrow().restartable());
        hold.countDown();
        a.close();
    }

    @Test
    void theCallSurvivesTheRoundTripThroughJson() {
        ToolCall original = new ToolCall("job", Json.createObjectBuilder().add("n", "7").build(), TASKS, Json.createObjectBuilder().add("name", "client").build(), Json.createObjectBuilder().add("k", Json.createObjectBuilder().add("action", "accept")).build(), "state-1", ALICE);
        ToolCall restored = TaskService.callFromJson("job", TaskService.callToJson(original));
        assertEquals(original.arguments(), restored.arguments());
        assertEquals(original.clientCapabilities(), restored.clientCapabilities());
        assertEquals(original.clientInfo(), restored.clientInfo());
        assertEquals(original.inputResponses(), restored.inputResponses());
        assertEquals("state-1", restored.requestState());
        assertEquals(ALICE, restored.caller());
        assertNotNull(restored.progress());
    }


    private ToolResult.Task first() throws Exception {
        return (ToolResult.Task) callTool();
    }

    private ToolResult callTool() throws Exception {
        return tool.call(call());
    }

    private ToolCall call() {
        return new ToolCall("job", Json.createObjectBuilder().add("n", "7").build(), TASKS, null, null, null, ALICE);
    }

    private TaskService service(int maxAttempts) {
        TaskConfig config = new TaskConfig(true, 3_600_000L, 2_000L, 1024 * 1024, 100, null, 10_000L, 30_000L, 60_000L, 100L, 60_000L, 2_000L, maxAttempts);
        return new TaskService(store, config, McpRegistry.of(List.of(tool)), clock, Executors.newVirtualThreadPerTaskExecutor());
    }

    private TaskRecord awaitStatus(String id, String status) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        TaskRecord rec = store.find(id).orElseThrow();
        while (System.currentTimeMillis() < deadline && !rec.status().wire().equals(status)) {
            Thread.sleep(25);
            rec = store.find(id).orElseThrow();
        }
        assertEquals(status, rec.status().wire());
        return rec;
    }


    private static final class TestClock extends Clock {

        private volatile Instant now = Instant.parse("2026-11-11T10:00:00Z");


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
