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

/** A tool that returns a JAX-RS {@code Response}: the status decides between a result and an error. */
class ResponseReturnIT {

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
    void a200WithAnEntityIsTheEntityAsStructuredContent() throws Exception {
        JsonObject result = call("response_ok", Json.createObjectBuilder().add("duck", "Donald").build());
        assertFalse(result.getBoolean("isError", false), result.toString());
        JsonObject booking = result.getJsonObject("structuredContent");
        assertEquals("Donald", booking.getString("duck"));
        assertEquals(2, booking.getInt("times"));
    }

    @Test
    void a204IsAnEmptyResult() throws Exception {
        JsonObject result = call("response_empty", Json.createObjectBuilder().build());
        assertFalse(result.getBoolean("isError", false), result.toString());
        assertEquals(0, result.getJsonArray("content").size());
    }

    @Test
    void a404WithATextShowsTheTextAsAnError() throws Exception {
        JsonObject result = call("response_not_found", Json.createObjectBuilder().add("duck", "Daisy").build());
        assertTrue(result.getBoolean("isError", false), result.toString());
        assertEquals("No duck named Daisy", text(result));
    }

    @Test
    void a409WithoutAnEntityShowsTheStatus() throws Exception {
        JsonObject result = call("response_conflict", Json.createObjectBuilder().build());
        assertTrue(result.getBoolean("isError", false), result.toString());
        assertEquals("HTTP 409 Conflict", text(result));
    }

    @Test
    void a500IsTheGenericErrorAndTheEntityStaysInTheLog() throws Exception {
        McpClient.Reply reply = client.toolsCall("response_failure", Json.createObjectBuilder().build(), null);
        JsonObject result = reply.json().getJsonObject("result");
        assertTrue(result.getBoolean("isError", false), reply.body());
        assertEquals("Tool execution failed", text(result));
        assertFalse(reply.body().contains("hunter2"), reply.body());
    }


    private static JsonObject call(String tool, JsonObject arguments) throws Exception {
        McpClient.Reply reply = client.toolsCall(tool, arguments, null);
        assertEquals(200, reply.status(), reply.body());
        assertFalse(reply.json().containsKey("error"), reply.body());
        return reply.json().getJsonObject("result");
    }

    private static String text(JsonObject result) {
        return result.getJsonArray("content").getJsonObject(0).getString("text");
    }
}
