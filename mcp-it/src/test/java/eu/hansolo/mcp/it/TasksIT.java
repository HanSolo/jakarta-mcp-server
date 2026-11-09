package eu.hansolo.mcp.it;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Tasks extension on a real server: create, poll, input, cancel, failure and the rules around them. */
class TasksIT {

    private static final String TASKS = "io.modelcontextprotocol/tasks";

    private static GenericContainer<?> container;
    private static McpClient           client;


    @BeforeAll
    static void start() {
        Assumptions.assumeTrue(ServerFixture.dockerAvailable(), "Docker is not available: skipping integration tests");
        container = ServerFixture.create(ServerFixture.fixtureWar(), Map.of("MCP_TASKS_ENABLED", "true"), false);
        container.start();
        client = new McpClient(container.getHost(), container.getMappedPort(ServerFixture.httpPort()));
        ServerFixture.awaitDeployed(container, client);
    }

    @AfterAll
    static void stop() {
        if (container != null) {
            container.stop();
        }
    }


    private static McpClient.Reply rpc(String method, String name, JsonObjectBuilder params, boolean declareTasks, String nameHeader) throws Exception {
        JsonObjectBuilder capabilities = Json.createObjectBuilder();
        if (declareTasks) {
            capabilities.add("extensions", Json.createObjectBuilder().add(TASKS, Json.createObjectBuilder()));
        }
        JsonObject meta = Json.createObjectBuilder().add("io.modelcontextprotocol/protocolVersion", "2026-07-28").add("io.modelcontextprotocol/clientInfo", Json.createObjectBuilder().add("name", "mcp-it").add("version", "0")).add("io.modelcontextprotocol/clientCapabilities", capabilities).build();
        String body = Json.createObjectBuilder().add("jsonrpc", "2.0").add("id", 1).add("method", method).add("params", params.add("_meta", meta)).build().toString();
        return client.post(McpClient.standardHeaders(method, nameHeader), body);
    }

    private static JsonObject callDemo(String mode, boolean declareTasks) throws Exception {
        McpClient.Reply reply = rpc("tools/call", "task_demo", Json.createObjectBuilder().add("name", "task_demo").add("arguments", Json.createObjectBuilder().add("mode", mode).add("millis", 200)), declareTasks, "task_demo");
        assertEquals(200, reply.status(), reply.body());
        return reply.json().getJsonObject("result");
    }

    private static JsonObject get(String taskId) throws Exception {
        McpClient.Reply reply = rpc("tasks/get", taskId, Json.createObjectBuilder().add("taskId", taskId), true, taskId);
        assertEquals(200, reply.status(), reply.body());
        return reply.json().getJsonObject("result");
    }

    private static JsonObject awaitStatus(String taskId, String status) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        JsonObject last = null;
        while (System.currentTimeMillis() < deadline) {
            last = get(taskId);
            if (last.getString("status").equals(status)) {
                return last;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Task never reached " + status + "; last seen: " + last);
    }


    @Test
    void discoverAnnouncesTheExtension() throws Exception {
        McpClient.Reply reply = rpc("server/discover", null, Json.createObjectBuilder(), false, null);
        assertTrue(reply.json().getJsonObject("result").getJsonObject("capabilities").getJsonObject("extensions").containsKey(TASKS), reply.body());
    }

    @Test
    void aClientThatDeclaredTasksGetsATaskAndPollsItToTheResult() throws Exception {
        JsonObject created = callDemo("quick", true);
        assertEquals("task", created.getString("resultType"));
        assertTrue(created.getString("taskId").length() >= 32);
        JsonObject done = awaitStatus(created.getString("taskId"), "completed");
        assertEquals("finished quick", done.getJsonObject("result").getJsonArray("content").getJsonObject(0).getString("text"));
    }

    @Test
    void aClientWithoutTheExtensionGetsThePlainResult() throws Exception {
        JsonObject plain = callDemo("quick", false);
        assertEquals("complete", plain.getString("resultType"));
        assertEquals("finished quick", plain.getJsonArray("content").getJsonObject(0).getString("text"));
    }

    @Test
    void workCanAskForInputThroughTasksUpdate() throws Exception {
        String id = callDemo("ask", true).getString("taskId");
        JsonObject waiting = awaitStatus(id, "input_required");
        assertTrue(waiting.getJsonObject("inputRequests").containsKey("confirm"));
        JsonObject answer = Json.createObjectBuilder().add("confirm", Json.createObjectBuilder().add("action", "accept").add("content", Json.createObjectBuilder().add("why", "because"))).build();
        McpClient.Reply ack = rpc("tasks/update", id, Json.createObjectBuilder().add("taskId", id).add("inputResponses", answer), true, id);
        assertEquals(200, ack.status(), ack.body());
        JsonObject done = awaitStatus(id, "completed");
        assertEquals("confirmed: because", done.getJsonObject("result").getJsonArray("content").getJsonObject(0).getString("text"));
    }

    @Test
    void aCancelledTaskEndsAsCancelled() throws Exception {
        String id = callDemo("wait", true).getString("taskId");
        assertEquals("working", awaitStatus(id, "working").getString("status"));
        McpClient.Reply ack = rpc("tasks/cancel", id, Json.createObjectBuilder().add("taskId", id), true, id);
        assertEquals(200, ack.status(), ack.body());
        awaitStatus(id, "cancelled");
    }

    @Test
    void aFailedTaskCarriesTheJsonRpcError() throws Exception {
        String id = callDemo("fail", true).getString("taskId");
        JsonObject failed = awaitStatus(id, "failed");
        assertEquals(-32602, failed.getJsonObject("error").getInt("code"));
        assertEquals("the demo failed on purpose", failed.getJsonObject("error").getString("message"));
        assertFalse(failed.containsKey("result"));
    }

    @Test
    void unknownTasksMissingCapabilityAndWrongNameHeaderAreRefused() throws Exception {
        McpClient.Reply unknown = rpc("tasks/get", "no-such-task", Json.createObjectBuilder().add("taskId", "no-such-task"), true, "no-such-task");
        assertEquals(-32602, unknown.json().getJsonObject("error").getInt("code"));
        McpClient.Reply undeclared = rpc("tasks/get", "x", Json.createObjectBuilder().add("taskId", "x"), false, "x");
        assertEquals(400, undeclared.status());
        assertEquals(-32021, undeclared.json().getJsonObject("error").getInt("code"));
        McpClient.Reply mismatch = rpc("tasks/get", "y", Json.createObjectBuilder().add("taskId", "x"), true, "y");
        assertEquals(400, mismatch.status());
        assertEquals(-32020, mismatch.json().getJsonObject("error").getInt("code"));
    }

    @Test
    void aListenStreamPushesTheStateOfAWatchedTaskUntilItsResult() throws Exception {
        String id = callDemo("quick", true).getString("taskId");
        JsonObject filter = Json.createObjectBuilder().add("taskIds", Json.createArrayBuilder().add(id).add("no-such-task")).build();
        JsonObject capabilities = Json.createObjectBuilder().add("extensions", Json.createObjectBuilder().add(TASKS, Json.createObjectBuilder())).build();
        try (McpClient.Events events = client.openListen(91, filter, null, "application/json, text/event-stream", capabilities)) {
            assertEquals(200, events.status());
            JsonObject ack = events.nextWithin(java.time.Duration.ofSeconds(20)).json();
            assertEquals("notifications/subscriptions/acknowledged", ack.getString("method"));
            assertEquals(1, ack.getJsonObject("params").getJsonObject("notifications").getJsonArray("taskIds").size());
            JsonObject params;
            do {
                JsonObject event = events.nextWithin(java.time.Duration.ofSeconds(20)).json();
                assertEquals("notifications/tasks", event.getString("method"));
                assertEquals(91, event.getJsonObject("params").getJsonObject("_meta").getInt("io.modelcontextprotocol/subscriptionId"));
                params = event.getJsonObject("params");
                assertEquals(id, params.getString("taskId"));
            } while (!params.getString("status").equals("completed"));
            assertEquals("finished quick", params.getJsonObject("result").getJsonArray("content").getJsonObject(0).getString("text"));
        }
    }

    @Test
    void askingForTaskNotificationsWithoutTheExtensionIsRefused() throws Exception {
        JsonObject filter = Json.createObjectBuilder().add("taskIds", Json.createArrayBuilder().add("abc")).build();
        try (McpClient.Events events = client.openListen(92, filter, null, "application/json, text/event-stream")) {
            assertEquals(400, events.status());
        }
    }
}
