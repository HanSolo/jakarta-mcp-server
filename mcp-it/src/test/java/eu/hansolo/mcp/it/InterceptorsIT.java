package eu.hansolo.mcp.it;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit, a policy gate and a rate limit around tools, built from a plain CDI interceptor ({@code AuditInterceptor} in the fixture)
 * and nothing from the library. The question this answers: does an application get these cross-cutting checks without the
 * library offering a hook? That holds only if the library calls tools through the CDI proxy, for {@code @McpTool} methods and for
 * {@code McpToolHandler} beans alike, so each case is checked on a real server.
 */
class InterceptorsIT {

    private static GenericContainer<?> container;
    private static McpClient           client;


    @BeforeAll
    static void start() {
        Assumptions.assumeTrue(ServerFixture.dockerAvailable(), "Docker is not available: skipping integration tests");
        container = ServerFixture.create(ServerFixture.fixtureWar(), Map.of(), false);
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

    @BeforeEach
    void clearTheLog() throws Exception {
        assertEquals(204, client.request("DELETE", "/admin/calls", null).status());
    }


    @Test
    void aToolMethodIsCalledThroughTheInterceptorAndTheInterceptorSeesTheArguments() throws Exception {
        JsonObject result = call("audited_echo", "hi");
        assertFalse(result.getBoolean("isError", false), result.toString());
        assertEquals("echo: hi", text(result));
        assertEquals(List.of("call audited_echo hi", "ran audited_echo hi"), log());
    }

    @Test
    void aPolicyGateInTheInterceptorStopsTheCallBeforeTheMethodRuns() throws Exception {
        JsonObject result = call("audited_echo", "blocked");
        assertTrue(result.getBoolean("isError", false), result.toString());
        assertEquals("denied by policy", text(result));
        assertEquals(List.of("call audited_echo blocked", "refused audited_echo denied by policy"), log(), "the tool itself never ran");
    }

    @Test
    void aRateLimitInTheInterceptorRefusesTheCallsAfterTheThird() throws Exception {
        for (int i = 1; i <= 3; i++) {
            assertFalse(call("audited_echo", "burst" + i).getBoolean("isError", false), "call " + i + " is within the limit");
        }
        JsonObject fourth = call("audited_echo", "burst4");
        assertTrue(fourth.getBoolean("isError", false), fourth.toString());
        assertEquals("rate limit exceeded", text(fourth));
        assertFalse(log().contains("ran audited_echo burst4"));
        assertFalse(call("audited_echo", "calm").getBoolean("isError", false), "other texts are not counted");
    }

    @Test
    void aToolWrittenAsAHandlerBeanGetsTheSameTreatment() throws Exception {
        JsonObject ok = call("audited_handler", "hi");
        assertEquals("handler echo: hi", text(ok));

        JsonObject refused = call("audited_handler", "blocked");
        assertTrue(refused.getBoolean("isError", false), refused.toString());
        assertEquals("denied by policy", text(refused));

        assertEquals(List.of("call call hi", "ran audited_handler hi", "call call blocked", "refused call denied by policy"), log(),
                     "only call() is audited: the binding is on that method, so name() and description() are not logged");
    }


    private static JsonObject call(String tool, String message) throws Exception {
        McpClient.Reply reply = client.toolsCall(tool, Json.createObjectBuilder().add("message", message).build(), null);
        assertEquals(200, reply.status(), reply.body());
        JsonObject json = reply.json();
        assertFalse(json.containsKey("error"), reply.body());
        return json.getJsonObject("result");
    }

    private static String text(JsonObject result) {
        return result.getJsonArray("content").getJsonObject(0).getString("text");
    }

    private static List<String> log() throws Exception {
        McpClient.Reply reply = client.request("GET", "/admin/calls", null);
        assertEquals(200, reply.status(), reply.body());
        JsonArray array = Json.createReader(new java.io.StringReader(reply.body())).readArray();
        return array.getValuesAs(jakarta.json.JsonString.class).stream().map(jakarta.json.JsonString::getString).toList();
    }
}
