package eu.hansolo.mcp.it;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The support-ticket example end to end: JPA (embedded H2) behind the MCP layer, scopes from a real Keycloak. The example needs
 * authentication because its primitives declare scopes, so there is no unauthenticated variant of this test.
 *
 * <p>Each ticket created by a test gets its own number, and the seed data is never closed except by the one test that closes a
 * ticket it created itself, so the tests do not depend on their order.
 */
class McpTicketsKeycloakIT {

    private static final JsonObject NONE   = Json.createObjectBuilder().build();
    private static final JsonObject ELICIT = Json.createObjectBuilder().add("elicitation", Json.createObjectBuilder()).build();

    private static KeycloakSupport    keycloak;
    private static GenericContainer<?> server;
    private static McpClient          client;
    private static String             reader;
    private static String             writer;


    @BeforeAll
    static void start() throws Exception {
        Assumptions.assumeTrue(ServerFixture.dockerAvailable(), "Docker is not available: skipping integration tests");
        keycloak = new KeycloakSupport();
        keycloak.start();
        server = ServerFixture.create(Path.of(System.getProperty("mcp.tickets.war", "../mcp-example-tickets/target/mcp-example-tickets.war")), keycloak.serverEnvironment("mcp:tools,tickets:read,tickets:write", "mcp:tools"), false);
        server.withNetwork(keycloak.network);
        server.start();
        client = new McpClient(server.getHost(), server.getMappedPort(ServerFixture.httpPort()));
        ServerFixture.awaitDeployed(server, client);
        reader = keycloak.token("mcp-test", "mcp:tools tickets:read");
        writer = keycloak.token("mcp-test", "mcp:tools tickets:read tickets:write");
    }

    @AfterAll
    static void stop() {
        if (server != null) {
            server.stop();
        }
        if (keycloak != null) {
            keycloak.stop();
        }
    }

    private static JsonObject result(McpClient.Reply reply) {
        assertEquals(200, reply.status(), reply.body());
        JsonObject json = reply.json();
        assertTrue(json.containsKey("result"), reply.body());
        return json.getJsonObject("result");
    }

    private static JsonObject structured(McpClient.Reply reply) {
        JsonObject result = result(reply);
        assertFalse(result.getBoolean("isError", false), reply.body());
        return result.getJsonObject("structuredContent");
    }

    private static List<String> names(JsonArray array, String key) {
        return array.getValuesAs(JsonObject.class).stream().map(o -> o.getString(key)).sorted().toList();
    }

    private static McpClient.Reply call(String tool, JsonObject arguments, String token, JsonObject capabilities) throws Exception {
        return client.rpc("tools/call", 1, Json.createObjectBuilder().add("name", tool).add("arguments", arguments), tool, token, capabilities);
    }

    @Test
    void toolsAreFilteredByTheScopesOfTheToken() throws Exception {
        assertEquals(List.of("get_ticket", "search_tickets"), names(result(client.toolsList(reader)).getJsonArray("tools"), "name"));
        assertEquals(List.of("close_ticket", "create_ticket", "get_ticket", "search_tickets"), names(result(client.toolsList(writer)).getJsonArray("tools"), "name"));
        assertEquals(List.of(), names(result(client.toolsList(keycloak.token("mcp-test", "mcp:tools"))).getJsonArray("tools"), "name"));
    }

    @Test
    void searchPagesThroughTheSeedData() throws Exception {
        JsonObject first = structured(call("search_tickets", Json.createObjectBuilder().add("limit", 50).build(), reader, NONE));
        assertTrue(first.getInt("total") >= 120, first.toString());
        assertEquals(50, first.getJsonArray("tickets").size());
        assertEquals(50, first.getInt("nextOffset"));
        JsonObject second = structured(call("search_tickets", Json.createObjectBuilder().add("limit", 50).add("offset", 50).build(), reader, NONE));
        assertEquals(50, second.getJsonArray("tickets").size());
        assertTrue(second.getJsonArray("tickets").getJsonObject(0).getInt("id") < first.getJsonArray("tickets").getJsonObject(49).getInt("id"));
        JsonObject vpn = structured(call("search_tickets", Json.createObjectBuilder().add("text", "vpn drops").add("status", "OPEN").build(), reader, NONE));
        assertTrue(vpn.getInt("total") > 0);
        assertTrue(vpn.getJsonArray("tickets").getJsonObject(0).getString("title").startsWith("VPN drops"));
    }

    @Test
    void anOutOfRangeLimitIsAnErrorTheModelCanRead() throws Exception {
        JsonObject result = result(call("search_tickets", Json.createObjectBuilder().add("limit", 500).build(), reader, NONE));
        assertTrue(result.getBoolean("isError"));
        assertTrue(result.getJsonArray("content").getJsonObject(0).getString("text").contains("limit"));
    }

    @Test
    void aReadOnlyTokenCannotCreateTickets() throws Exception {
        McpClient.Reply reply = call("create_ticket", Json.createObjectBuilder().add("title", "t").add("description", "d").build(), reader, NONE);
        assertEquals(403, reply.status());
        assertTrue(reply.header("WWW-Authenticate").contains("scope=\"tickets:write\""), reply.header("WWW-Authenticate"));
    }

    @Test
    void createdTicketsCanBeReadBack() throws Exception {
        JsonObject created = structured(call("create_ticket", Json.createObjectBuilder().add("title", "Coffee machine").add("description", "No coffee").add("priority", "HIGH").build(), writer, NONE));
        assertEquals("OPEN", created.getString("status"));
        assertEquals("HIGH", created.getString("priority"));
        assertFalse(created.getString("createdBy").isBlank());
        JsonObject fetched = structured(call("get_ticket", Json.createObjectBuilder().add("id", created.getInt("id")).build(), reader, NONE));
        assertEquals("Coffee machine", fetched.getString("title"));
    }

    @Test
    void closingAsksTheUserAndOnlyClosesOnAccept() throws Exception {
        int id = structured(call("create_ticket", Json.createObjectBuilder().add("title", "To close").add("description", "d").build(), writer, NONE)).getInt("id");
        JsonObject arguments = Json.createObjectBuilder().add("id", id).add("resolution", "done").build();

        assertEquals(400, call("close_ticket", arguments, writer, NONE).status(), "a client that cannot ask the user must not close");

        JsonObject asked = result(call("close_ticket", arguments, writer, ELICIT));
        assertEquals("input_required", asked.getString("resultType"));
        assertEquals("elicitation/create", asked.getJsonObject("inputRequests").getJsonObject("confirm").getString("method"));
        assertEquals("OPEN", structured(call("get_ticket", Json.createObjectBuilder().add("id", id).build(), reader, NONE)).getString("status"));

        JsonObject declined = retry("close_ticket", arguments, "decline");
        assertTrue(declined.getBoolean("isError"));
        assertEquals("OPEN", structured(call("get_ticket", Json.createObjectBuilder().add("id", id).build(), reader, NONE)).getString("status"));

        JsonObject accepted = retry("close_ticket", arguments, "accept");
        assertEquals("complete", accepted.getString("resultType"));
        assertFalse(accepted.getBoolean("isError", false));
        JsonObject closed = structured(call("get_ticket", Json.createObjectBuilder().add("id", id).build(), reader, NONE));
        assertEquals("CLOSED", closed.getString("status"));
        assertEquals("done", closed.getString("resolution"));
    }

    /** Repeats the call with the user's answer attached, the second half of a multi round-trip request. */
    private static JsonObject retry(String tool, JsonObject arguments, String action) throws Exception {
        JsonObject params = Json.createObjectBuilder().add("name", tool).add("arguments", arguments).add("inputResponses", Json.createObjectBuilder().add("confirm", Json.createObjectBuilder().add("action", action))).build();
        return result(client.rpc("tools/call", 2, Json.createObjectBuilder(params), tool, writer, ELICIT));
    }

    @Test
    void resourcesExposeTicketsAndStatistics() throws Exception {
        JsonObject templates = result(client.rpc("resources/templates/list", 1, Json.createObjectBuilder(), null, reader, NONE));
        assertEquals("ticket://{id}", templates.getJsonArray("resourceTemplates").getJsonObject(0).getString("uriTemplate"));
        JsonObject one = result(client.rpc("resources/read", 1, Json.createObjectBuilder().add("uri", "ticket://1"), "ticket://1", reader, NONE));
        assertTrue(one.getJsonArray("contents").getJsonObject(0).getString("text").contains("\"id\":1"));
        JsonObject stats = result(client.rpc("resources/read", 1, Json.createObjectBuilder().add("uri", "ticket://stats"), "ticket://stats", reader, NONE));
        assertTrue(stats.getJsonArray("contents").getJsonObject(0).getString("text").contains("\"openByPriority\""));
        JsonObject missing = client.rpc("resources/read", 1, Json.createObjectBuilder().add("uri", "ticket://999999"), "ticket://999999", reader, NONE).json().getJsonObject("error");
        assertEquals(-32602, missing.getInt("code"));
        assertEquals("ticket://999999", missing.getJsonObject("data").getString("uri"));
    }

    @Test
    void theTriagePromptAndItsCompletion() throws Exception {
        JsonObject prompt = result(client.rpc("prompts/get", 1, Json.createObjectBuilder().add("name", "triage").add("arguments", Json.createObjectBuilder().add("ticketId", "1")), "triage", reader, NONE));
        assertTrue(prompt.getJsonArray("messages").getJsonObject(0).getJsonObject("content").getString("text").contains("Ticket 1 "));

        JsonObject params = Json.createObjectBuilder().add("ref", Json.createObjectBuilder().add("type", "ref/prompt").add("name", "triage")).add("argument", Json.createObjectBuilder().add("name", "ticketId").add("value", "11")).build();
        JsonObject completion = result(client.rpc("completion/complete", 1, Json.createObjectBuilder(params), null, reader, NONE)).getJsonObject("completion");
        List<String> values = completion.getJsonArray("values").getValuesAs(JsonString.class).stream().map(JsonString::getString).toList();
        assertTrue(values.contains("11") && values.contains("110"), values.toString());
    }
}
