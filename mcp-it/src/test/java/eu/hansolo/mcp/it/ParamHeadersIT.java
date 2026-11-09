package eu.hansolo.mcp.it;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code x-mcp-header} annotation and the {@code Mcp-Param-*} request headers on a real server. */
class ParamHeadersIT {

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


    private static McpClient.Reply call(JsonObject arguments, String... headerPairs) throws Exception {
        Map<String, String> headers = McpClient.standardHeaders("tools/call", "routed_echo");
        for (int i = 0; i < headerPairs.length; i += 2) {
            headers.put(headerPairs[i], headerPairs[i + 1]);
        }
        JsonObject meta = Json.createObjectBuilder().add("io.modelcontextprotocol/protocolVersion", "2026-07-28").add("io.modelcontextprotocol/clientInfo", Json.createObjectBuilder().add("name", "mcp-it").add("version", "0")).add("io.modelcontextprotocol/clientCapabilities", Json.createObjectBuilder()).build();
        JsonObject params = Json.createObjectBuilder().add("name", "routed_echo").add("arguments", arguments).add("_meta", meta).build();
        String body = Json.createObjectBuilder().add("jsonrpc", "2.0").add("id", 1).add("method", "tools/call").add("params", params).build().toString();
        return client.post(headers, body);
    }

    private static void assertHeaderMismatch(McpClient.Reply reply) {
        assertEquals(400, reply.status());
        assertEquals(-32020, reply.json().getJsonObject("error").getInt("code"));
    }


    @Test
    void toolsListCarriesTheAnnotation() throws Exception {
        JsonObject tool = client.toolsList(null).json().getJsonObject("result").getJsonArray("tools").getValuesAs(JsonObject.class).stream().filter(t -> t.getString("name").equals("routed_echo")).findFirst().orElseThrow();
        assertEquals("Region", tool.getJsonObject("inputSchema").getJsonObject("properties").getJsonObject("region").getString("x-mcp-header"));
    }

    @Test
    void matchingHeadersAreAccepted() throws Exception {
        JsonObjectBuilder target = Json.createObjectBuilder().add("zone", "a");
        JsonObject args = Json.createObjectBuilder().add("region", "eu").add("count", 42).add("urgent", true).add("target", target).build();
        McpClient.Reply reply = call(args, "Mcp-Param-Region", "eu", "Mcp-Param-Count", "42", "Mcp-Param-Urgent", "true", "Mcp-Param-Zone", "a");
        assertEquals(200, reply.status());
        assertEquals("eu", reply.json().getJsonObject("result").getJsonObject("structuredContent").getString("region"));
    }

    @Test
    void aMissingHeaderIsAHeaderMismatch() throws Exception {
        assertHeaderMismatch(call(Json.createObjectBuilder().add("region", "eu").build()));
    }

    @Test
    void aDifferingHeaderIsAHeaderMismatch() throws Exception {
        assertHeaderMismatch(call(Json.createObjectBuilder().add("region", "eu").build(), "Mcp-Param-Region", "us"));
        assertHeaderMismatch(call(Json.createObjectBuilder().add("count", 1).build(), "Mcp-Param-Count", "2"));
        assertHeaderMismatch(call(Json.createObjectBuilder().add("urgent", false).build(), "Mcp-Param-Urgent", "true"));
    }

    @Test
    void base64ValuesAreDecoded() throws Exception {
        String text    = "Zürich";
        String encoded = "=?base64?" + Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)) + "?=";
        assertEquals(200, call(Json.createObjectBuilder().add("region", text).build(), "Mcp-Param-Region", encoded).status());
        assertHeaderMismatch(call(Json.createObjectBuilder().add("region", text).build(), "Mcp-Param-Region", "=?base64?%%%?="));
    }

    @Test
    void anArgumentThatIsAbsentNeedsNoHeader() throws Exception {
        assertEquals(200, call(Json.createObjectBuilder().add("note", "x").build()).status());
    }

    @Test
    void unknownParamHeadersAreIgnored() throws Exception {
        McpClient.Reply reply = call(Json.createObjectBuilder().add("note", "x").build(), "Mcp-Param-Other", "whatever");
        assertEquals(200, reply.status());
        assertTrue(reply.json().containsKey("result"));
    }
}
