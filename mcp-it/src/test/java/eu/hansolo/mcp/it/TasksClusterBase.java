package eu.hansolo.mcp.it;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two server instances and one database on a Docker network (the database comes from the subclass): the JDBC task store makes a task created on one instance
 * visible, answerable and cancellable on the other, and an instance that dies mid-task is noticed by the survivor.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class TasksClusterBase {

    private static final String TASKS = "io.modelcontextprotocol/tasks";

    /** The database of a variant: its container (with the network alias the servers use), the JDBC URL as the servers see it, user and password. */
    protected record Database(GenericContainer<?> container, String url, String user, String password) {}

    private Network             network;
    private GenericContainer<?> database;
    private GenericContainer<?> a;
    private GenericContainer<?> b;
    private McpClient           clientA;
    private McpClient           clientB;


    /** Creates, without starting it, the database for this variant on the given network. */
    protected abstract Database database(Network network);


    @BeforeAll
    void start() {
        Assumptions.assumeTrue(ServerFixture.dockerAvailable(), "Docker is not available: skipping integration tests");
        network = Network.newNetwork();
        Database db = database(network);
        database = db.container();
        database.start();
        Map<String, String> env = new HashMap<>();
        env.put("MCP_TASKS_ENABLED", "true");
        env.put("MCP_TASKS_JDBC_URL", db.url());
        env.put("MCP_TASKS_JDBC_USER", db.user());
        env.put("MCP_TASKS_JDBC_PASSWORD", db.password());
        env.put("MCP_TASKS_JDBC_CREATE_SCHEMA", "true");
        env.put("MCP_TASKS_HEARTBEAT_MS", "300");
        env.put("MCP_TASKS_LEASE_MS", "2000");
        env.put("MCP_TASKS_SWEEP_MS", "500");
        env.put("MCP_TASKS_INPUT_POLL_MS", "100");
        env.put("MCP_TASKS_NOTIFY_POLL_MS", "200");
        int port = ServerFixture.httpPort();
        a = ServerFixture.create(ServerFixture.fixtureWar(), env, false).withNetwork(network).withNetworkAliases("mcp-a");
        b = ServerFixture.create(ServerFixture.fixtureWar(), env, false).withNetwork(network).withNetworkAliases("mcp-b");
        // one after the other: both would otherwise try to create the table at the same moment
        a.start();
        clientA = new McpClient(a.getHost(), a.getMappedPort(port));
        ServerFixture.awaitDeployed(a, clientA);
        b.start();
        clientB = new McpClient(b.getHost(), b.getMappedPort(port));
        ServerFixture.awaitDeployed(b, clientB);
    }

    @AfterAll
    void stop() {
        for (GenericContainer<?> container : new GenericContainer<?>[] {b, a, database}) {
            if (container != null) {
                try {
                    container.stop();
                } catch (RuntimeException ignored) {
                    // already gone
                }
            }
        }
        if (network != null) {
            network.close();
        }
    }


    private static McpClient.Reply rpc(McpClient client, String method, String name, JsonObjectBuilder params) throws Exception {
        JsonObject capabilities = Json.createObjectBuilder().add("extensions", Json.createObjectBuilder().add(TASKS, Json.createObjectBuilder())).build();
        JsonObject meta = Json.createObjectBuilder().add("io.modelcontextprotocol/protocolVersion", "2026-07-28").add("io.modelcontextprotocol/clientInfo", Json.createObjectBuilder().add("name", "mcp-it").add("version", "0")).add("io.modelcontextprotocol/clientCapabilities", capabilities).build();
        String body = Json.createObjectBuilder().add("jsonrpc", "2.0").add("id", 1).add("method", method).add("params", params.add("_meta", meta)).build().toString();
        return client.post(McpClient.standardHeaders(method, name), body);
    }

    private static String startDemo(McpClient client, String mode) throws Exception {
        McpClient.Reply reply = rpc(client, "tools/call", "task_demo", Json.createObjectBuilder().add("name", "task_demo").add("arguments", Json.createObjectBuilder().add("mode", mode).add("millis", 200)));
        assertEquals(200, reply.status(), reply.body());
        JsonObject result = reply.json().getJsonObject("result");
        assertEquals("task", result.getString("resultType"), reply.body());
        return result.getString("taskId");
    }

    private static JsonObject get(McpClient client, String taskId) throws Exception {
        McpClient.Reply reply = rpc(client, "tasks/get", taskId, Json.createObjectBuilder().add("taskId", taskId));
        assertEquals(200, reply.status(), reply.body());
        return reply.json().getJsonObject("result");
    }

    private static JsonObject awaitStatus(McpClient client, String taskId, String status) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        JsonObject last = null;
        while (System.currentTimeMillis() < deadline) {
            last = get(client, taskId);
            if (last.getString("status").equals(status)) {
                return last;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Task never reached " + status + "; last seen: " + last);
    }


    @Test
    @Order(1)
    void aTaskCreatedOnAIsPolledToItsResultOnB() throws Exception {
        String id = startDemo(clientA, "quick");
        JsonObject done = awaitStatus(clientB, id, "completed");
        assertEquals("finished quick", done.getJsonObject("result").getJsonArray("content").getJsonObject(0).getString("text"));
    }

    @Test
    @Order(2)
    void inputAnsweredOnBReachesTheWorkOnA() throws Exception {
        String id = startDemo(clientA, "ask");
        JsonObject waiting = awaitStatus(clientB, id, "input_required");
        assertTrue(waiting.getJsonObject("inputRequests").containsKey("confirm"));
        JsonObject answer = Json.createObjectBuilder().add("confirm", Json.createObjectBuilder().add("action", "accept").add("content", Json.createObjectBuilder().add("why", "from B"))).build();
        assertEquals(200, rpc(clientB, "tasks/update", id, Json.createObjectBuilder().add("taskId", id).add("inputResponses", answer)).status());
        JsonObject done = awaitStatus(clientA, id, "completed");
        assertEquals("confirmed: from B", done.getJsonObject("result").getJsonArray("content").getJsonObject(0).getString("text"));
    }

    @Test
    @Order(3)
    void aCancelOnBStopsTheWorkOnA() throws Exception {
        String id = startDemo(clientA, "wait");
        assertEquals("working", get(clientB, id).getString("status"));
        assertEquals(200, rpc(clientB, "tasks/cancel", id, Json.createObjectBuilder().add("taskId", id)).status());
        awaitStatus(clientB, id, "cancelled");
    }

    @Test
    @Order(4)
    void aStreamOnBHearsAboutATaskThatRunsOnA() throws Exception {
        String id = startDemo(clientA, "ask");
        JsonObject capabilities = Json.createObjectBuilder().add("extensions", Json.createObjectBuilder().add(TASKS, Json.createObjectBuilder())).build();
        JsonObject filter = Json.createObjectBuilder().add("taskIds", Json.createArrayBuilder().add(id)).build();
        try (McpClient.Events events = clientB.openListen(93, filter, null, "application/json, text/event-stream", capabilities)) {
            assertEquals(200, events.status());
            JsonObject ack = events.nextWithin(Duration.ofSeconds(20)).json();
            assertEquals(1, ack.getJsonObject("params").getJsonObject("notifications").getJsonArray("taskIds").size());
            JsonObject params = nextTaskEvent(events);
            while (!params.getString("status").equals("input_required")) {
                params = nextTaskEvent(events);
            }
            assertTrue(params.getJsonObject("inputRequests").containsKey("confirm"));
            JsonObject answer = Json.createObjectBuilder().add("confirm", Json.createObjectBuilder().add("action", "accept").add("content", Json.createObjectBuilder().add("why", "heard on B"))).build();
            assertEquals(200, rpc(clientB, "tasks/update", id, Json.createObjectBuilder().add("taskId", id).add("inputResponses", answer)).status());
            while (!params.getString("status").equals("completed")) {
                params = nextTaskEvent(events);
            }
            assertEquals("confirmed: heard on B", params.getJsonObject("result").getJsonArray("content").getJsonObject(0).getString("text"));
        }
    }

    private static JsonObject nextTaskEvent(McpClient.Events events) throws Exception {
        JsonObject event = events.nextWithin(Duration.ofSeconds(30)).json();
        assertEquals("notifications/tasks", event.getString("method"));
        return event.getJsonObject("params");
    }

    @Test
    @Order(5)
    void whenAIsKilledMidTaskBDeclaresTheTaskFailed() throws Exception {
        String id = startDemo(clientA, "wait");
        assertNotEquals("failed", get(clientB, id).getString("status"));
        a.getDockerClient().killContainerCmd(a.getContainerId()).exec();
        JsonObject lost = awaitStatus(clientB, id, "failed");
        assertEquals(-32603, lost.getJsonObject("error").getInt("code"));
    }
}
