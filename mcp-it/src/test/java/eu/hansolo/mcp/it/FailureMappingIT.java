package eu.hansolo.mcp.it;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the model is told when a tool throws. A JAX-RS {@code NotFoundException} shows its message through the library's own rule, an
 * exception that the fixture's {@code ToolExceptionMapper} explains shows the mapper's text, and anything else is replaced by the
 * generic text, so that connection strings and passwords stay in the server log.
 */
class FailureMappingIT {

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


    @Test
    void aJaxRsNotFoundExceptionShowsItsMessage() throws Exception {
        JsonObject result = call("fail_not_found", "Donald");
        assertTrue(result.getBoolean("isError", false), result.toString());
        assertEquals("No duck named Donald", text(result));
    }

    @Test
    void aMapperBeanOfTheApplicationChoosesTheMessage() throws Exception {
        JsonObject result = call("fail_business", "Daisy");
        assertTrue(result.getBoolean("isError", false), result.toString());
        assertEquals("Duck Daisy is not bookable today", text(result));
    }

    @Test
    void anExceptionNobodyExplainsGivesTheGenericTextAndNoDetails() throws Exception {
        McpClient.Reply reply = client.toolsCall("fail_secret", Json.createObjectBuilder().add("name", "Scrooge").build(), null);
        assertEquals(200, reply.status(), reply.body());
        JsonObject result = reply.json().getJsonObject("result");
        assertTrue(result.getBoolean("isError", false), result.toString());
        assertEquals("Tool execution failed", text(result));
        assertFalse(reply.body().contains("hunter2"), reply.body());
    }


    private static JsonObject call(String tool, String name) throws Exception {
        McpClient.Reply reply = client.toolsCall(tool, Json.createObjectBuilder().add("name", name).build(), null);
        assertEquals(200, reply.status(), reply.body());
        JsonObject json = reply.json();
        assertFalse(json.containsKey("error"), reply.body());
        return json.getJsonObject("result");
    }

    private static String text(JsonObject result) {
        return result.getJsonArray("content").getJsonObject(0).getString("text");
    }
}
