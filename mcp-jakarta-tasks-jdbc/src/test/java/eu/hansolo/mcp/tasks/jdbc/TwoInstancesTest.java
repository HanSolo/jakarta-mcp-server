package eu.hansolo.mcp.tasks.jdbc;

import eu.hansolo.mcp.api.Caller;
import eu.hansolo.mcp.api.Elicitation;
import eu.hansolo.mcp.api.InputRequests;
import eu.hansolo.mcp.api.RequestCancelledException;
import eu.hansolo.mcp.api.ToolCall;
import eu.hansolo.mcp.api.ToolResult;
import eu.hansolo.mcp.server.McpRegistry;
import eu.hansolo.mcp.server.TaskConfig;
import eu.hansolo.mcp.server.TaskService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Two task services over one database stand in for two server instances. */
class TwoInstancesTest {

    private static final Caller ALICE = new Caller("alice", Set.of(), JsonObject.EMPTY_JSON_OBJECT);

    private final TestClock    clock  = new TestClock();
    private final JdbcTaskStore store = new JdbcTaskStore(JdbcTaskStoreTest.dataSource(), "mcp_tasks", true);
    private final TaskService   a     = service();
    private final TaskService   b     = service();


    @Test
    void aTaskCreatedOnOneInstanceIsPolledOnTheOther() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        String id = a.create(ToolResult.task(ctx -> {
            release.await(5, TimeUnit.SECONDS);
            return ToolResult.text("from A");
        }), call()).getString("taskId");
        assertEquals("working", b.get(id, ALICE).getString("status"));
        release.countDown();
        JsonObject done = awaitStatus(b, id, "completed");
        assertEquals("from A", done.getJsonObject("result").getJsonArray("content").getJsonObject(0).getString("text"));
        close();
    }

    @Test
    void aCancelOnOneInstanceReachesTheWorkOnTheOther() throws Exception {
        String id = a.create(ToolResult.task(ctx -> {
            while (!ctx.isCancelled()) {
                Thread.sleep(10);
            }
            throw new RequestCancelledException("stop", null);
        }), call()).getString("taskId");
        b.cancel(id, ALICE);
        assertEquals("working", b.get(id, ALICE).getString("status"));
        a.heartbeat();
        awaitStatus(b, id, "cancelled");
        close();
    }

    @Test
    void inputAnsweredOnOneInstanceReachesTheWorkOnTheOther() throws Exception {
        String id = a.create(ToolResult.task(ctx -> {
            JsonObject answers = ctx.ask(InputRequests.of("confirm", Elicitation.form("Proceed?").string("why", p -> p.required())));
            return ToolResult.text("got " + answers.getJsonObject("confirm").getJsonObject("content").getString("why"));
        }), call()).getString("taskId");
        JsonObject waiting = awaitStatus(b, id, "input_required");
        assertTrue(waiting.getJsonObject("inputRequests").containsKey("confirm"));
        b.update(id, ALICE, Json.createObjectBuilder().add("confirm", Json.createObjectBuilder().add("action", "accept").add("content", Json.createObjectBuilder().add("why", "yes"))).build());
        JsonObject done = awaitStatus(b, id, "completed");
        assertEquals("got yes", done.getJsonObject("result").getJsonArray("content").getJsonObject(0).getString("text"));
        close();
    }

    @Test
    void whenTheInstanceRunningATaskDisappearsTheOtherOneDeclaresItFailed() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        String id = a.create(ToolResult.task(ctx -> {
            release.await(5, TimeUnit.SECONDS);
            return ToolResult.text("never stored");
        }), call()).getString("taskId");
        clock.advance(31_000);
        b.sweep();
        JsonObject lost = b.get(id, ALICE);
        assertEquals("failed", lost.getString("status"));
        release.countDown();
        Thread.sleep(150);
        assertEquals("failed", a.get(id, ALICE).getString("status"));
        close();
    }


    private TaskService service() {
        return new TaskService(store, new TaskConfig(true, 3_600_000L, 2_000L, 1024 * 1024, 100, null, 10_000L, 30_000L, 30_000L, 20L, 60_000L), McpRegistry.of(List.of()), clock, Executors.newVirtualThreadPerTaskExecutor());
    }

    private void close() {
        a.close();
        b.close();
    }

    private static ToolCall call() {
        return new ToolCall("work", JsonObject.EMPTY_JSON_OBJECT, JsonObject.EMPTY_JSON_OBJECT, null, null, null, ALICE);
    }

    private static JsonObject awaitStatus(TaskService service, String id, String status) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        JsonObject last = null;
        while (System.nanoTime() < deadline) {
            last = service.get(id, ALICE);
            if (last.getString("status").equals(status)) {
                return last;
            }
            Thread.sleep(20);
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
