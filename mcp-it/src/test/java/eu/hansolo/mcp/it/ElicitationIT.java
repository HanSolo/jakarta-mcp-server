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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Elicitation over Multi Round-Trip Requests on a real server: form and URL mode, a wrong answer, a refusal and a tampered state. */
class ElicitationIT {

    private static GenericContainer<?> container;
    private static McpClient           client;

    private static final JsonObject FORM = elicitation("form");
    private static final JsonObject URL  = elicitation("url");


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
    void theFirstCallAsksForAForm() throws Exception {
        JsonObject result = result(call("ask_contact", FORM, null, null));
        assertEquals("input_required", result.getString("resultType"));
        JsonObject params = result.getJsonObject("inputRequests").getJsonObject("contact").getJsonObject("params");
        assertEquals("form", params.getString("mode"));
        JsonObject schema = params.getJsonObject("requestedSchema");
        assertEquals("email", schema.getJsonObject("properties").getJsonObject("email").getString("format"));
        assertEquals(18, schema.getJsonObject("properties").getJsonObject("age").getInt("minimum"));
        assertEquals(2, schema.getJsonArray("required").size());
    }

    @Test
    void aGoodAnswerCompletesTheCall() throws Exception {
        JsonObject result = result(call("ask_contact", FORM, answer("accept", "{\"name\":\"Ada\",\"email\":\"ada@example.com\",\"age\":36}"), null));
        assertEquals("complete", result.getString("resultType"));
        assertEquals("Hello Ada <ada@example.com>, age 36", text(result));
    }

    @Test
    void anAnswerThatDoesNotFitIsAskedAgain() throws Exception {
        JsonObject result = result(call("ask_contact", FORM, answer("accept", "{\"name\":\"Ada\",\"email\":\"ada@example.com\",\"age\":17}"), null));
        assertEquals("input_required", result.getString("resultType"));
        assertTrue(result.getJsonObject("inputRequests").getJsonObject("contact").getJsonObject("params").getString("message").contains("'age'"));
    }

    @Test
    void declineAndCancelEndTheCall() throws Exception {
        assertEquals("declined", text(result(call("ask_contact", FORM, answer("decline", null), null))));
        assertEquals("cancelled", text(result(call("ask_contact", FORM, answer("cancel", null), null))));
    }

    @Test
    void aClientThatCannotElicitIsToldSo() throws Exception {
        JsonObject result = result(call("ask_contact", Json.createObjectBuilder().build(), null, null));
        assertTrue(result.getBoolean("isError"));
        assertEquals("This client cannot ask the user", text(result));
        assertTrue(result(call("ask_contact", URL, null, null)).getBoolean("isError"));
    }

    @Test
    void urlModeCarriesASignedStateThroughTheRetry() throws Exception {
        JsonObject first = result(call("connect_account", URL, null, null));
        assertEquals("input_required", first.getString("resultType"));
        JsonObject params = first.getJsonObject("inputRequests").getJsonObject("connect").getJsonObject("params");
        assertEquals("url", params.getString("mode"));
        assertEquals("https://accounts.example.com/connect?ticket=abc", params.getString("url"));
        String state = first.getString("requestState");
        assertNotNull(state);
        assertEquals("connected after connect", text(result(call("connect_account", URL, answer("accept", null), state))));
    }

    @Test
    void aTamperedStateIsRefused() throws Exception {
        String state = result(call("connect_account", URL, null, null)).getString("requestState");
        String tampered = state.substring(0, state.length() - 2) + (state.endsWith("AA") ? "BB" : "AA");
        McpClient.Reply reply = call("connect_account", URL, answer("accept", null), tampered);
        assertEquals(-32602, reply.json().getJsonObject("error").getInt("code"), reply.body());
        assertFalse(reply.body().contains("connected"));
    }


    private static McpClient.Reply call(String tool, JsonObject capabilities, JsonObject inputResponses, String state) throws Exception {
        JsonObjectBuilder params = Json.createObjectBuilder().add("name", tool);
        if (inputResponses != null) {
            params.add("inputResponses", inputResponses);
        }
        if (state != null) {
            params.add("requestState", state);
        }
        return client.rpc("tools/call", 1, params, tool, null, capabilities);
    }

    private static JsonObject result(McpClient.Reply reply) {
        assertEquals(200, reply.status(), reply.body());
        return reply.json().getJsonObject("result");
    }

    private static String text(JsonObject result) {
        return result.getJsonArray("content").getJsonObject(0).getString("text");
    }

    private static JsonObject elicitation(String mode) {
        return Json.createObjectBuilder().add("elicitation", Json.createObjectBuilder().add(mode, Json.createObjectBuilder())).build();
    }

    private static JsonObject answer(String action, String contentJson) {
        JsonObjectBuilder builder = Json.createObjectBuilder().add("action", action);
        if (contentJson != null) {
            builder.add("content", Json.createReader(new java.io.StringReader(contentJson)).readObject());
        }
        JsonObject answer = builder.build();   // build once: Parsson resets a builder when it is built
        return Json.createObjectBuilder().add("contact", answer).add("connect", answer).build();
    }
}
