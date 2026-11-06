package eu.hansolo.mcp.server;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import eu.hansolo.mcp.api.McpToolHandler;
import eu.hansolo.mcp.api.ToolCall;
import eu.hansolo.mcp.api.ToolResult;
import eu.hansolo.mcp.protocol.ErrorCodes;
import eu.hansolo.mcp.protocol.McpProtocolException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpRequestHandlerTest {

    private static final String VERSION = "2026-07-28";

    private final McpConfig         config  = new McpConfig(null, 1024, 5_000L, "test-server", "1.2.3", "Use the tools wisely.");
    private final McpRequestHandler handler = new McpRequestHandler(McpRegistry.of(List.of(new EchoTool(), new BoomTool(), new AskTool())), config);

    // ---- discovery -------------------------------------------------------------------------------------------------

    @Test
    void discoverAdvertisesVersionsCapabilitiesAndIdentity() {
        McpHttpResponse response = post("server/discover", "1", null, null);
        assertEquals(200, response.status());
        JsonObject result = json(response).getJsonObject("result");
        assertEquals("complete", result.getString("resultType"));
        assertEquals(VERSION, result.getJsonArray("supportedVersions").getString(0));
        assertTrue(result.getJsonObject("capabilities").containsKey("tools"));
        assertEquals("Use the tools wisely.", result.getString("instructions"));
        assertEquals(5_000, result.getJsonNumber("ttlMs").longValue());
        assertEquals("public", result.getString("cacheScope"));
        assertEquals("test-server", result.getJsonObject("_meta").getJsonObject("io.modelcontextprotocol/serverInfo").getString("name"));
        assertEquals(1, json(response).getInt("id"));
    }

    // ---- header and metadata validation ----------------------------------------------------------------------------

    @Test
    void missingProtocolVersionHeaderIsAHeaderMismatchThatNamesSupportedVersions() {
        Map<String, String> headers = headers("tools/list", null);
        headers.remove("MCP-Protocol-Version");
        McpHttpResponse response = send(headers, body("tools/list", "1", null));
        assertEquals(400, response.status());
        JsonObject error = json(response).getJsonObject("error");
        assertEquals(ErrorCodes.HEADER_MISMATCH, error.getInt("code"));
        assertTrue(error.getString("message").contains(VERSION));
    }

    @Test
    void legacyInitializeIsRejectedWithTheSupportedVersions() {
        String initialize = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},\"clientInfo\":{\"name\":\"old\",\"version\":\"1\"}}}";
        McpHttpResponse response = send(new TreeMap<>(String.CASE_INSENSITIVE_ORDER), initialize);
        assertEquals(400, response.status());
        assertTrue(json(response).getJsonObject("error").getString("message").contains(VERSION));
    }

    @Test
    void unsupportedVersionListsSupportedOnes() {
        Map<String, String> headers = headers("tools/list", null);
        headers.put("MCP-Protocol-Version", "1900-01-01");
        McpHttpResponse response = send(headers, body("tools/list", "7", null));
        assertEquals(400, response.status());
        JsonObject error = json(response).getJsonObject("error");
        assertEquals(ErrorCodes.UNSUPPORTED_PROTOCOL_VERSION, error.getInt("code"));
        assertEquals("1900-01-01", error.getJsonObject("data").getString("requested"));
        assertEquals(VERSION, error.getJsonObject("data").getJsonArray("supported").getString(0));
        assertEquals(7, json(response).getInt("id"));
    }

    @Test
    void headerAndBodyVersionMustMatch() {
        JsonObject params = Json.createObjectBuilder().add("_meta", Json.createObjectBuilder().add("io.modelcontextprotocol/protocolVersion", "2025-11-25").add("io.modelcontextprotocol/clientCapabilities", Json.createObjectBuilder())).build();
        String raw = Json.createObjectBuilder().add("jsonrpc", "2.0").add("id", 1).add("method", "tools/list").add("params", params).build().toString();
        McpHttpResponse response = send(headers("tools/list", null), raw);
        assertEquals(400, response.status());
        assertEquals(ErrorCodes.HEADER_MISMATCH, json(response).getJsonObject("error").getInt("code"));
    }

    @Test
    void missingMetaIsInvalidParams() {
        String raw = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}";
        McpHttpResponse response = send(headers("tools/list", null), raw);
        assertEquals(400, response.status());
        assertEquals(ErrorCodes.INVALID_PARAMS, json(response).getJsonObject("error").getInt("code"));
    }

    @Test
    void missingClientCapabilitiesIsInvalidParams() {
        JsonObject params = Json.createObjectBuilder().add("_meta", Json.createObjectBuilder().add("io.modelcontextprotocol/protocolVersion", VERSION)).build();
        String raw = Json.createObjectBuilder().add("jsonrpc", "2.0").add("id", 1).add("method", "tools/list").add("params", params).build().toString();
        McpHttpResponse response = send(headers("tools/list", null), raw);
        assertEquals(400, response.status());
        assertEquals(ErrorCodes.INVALID_PARAMS, json(response).getJsonObject("error").getInt("code"));
    }

    @Test
    void methodHeaderMustMatchBody() {
        McpHttpResponse response = send(headers("tools/call", "echo"), body("tools/list", "1", null));
        assertEquals(400, response.status());
        assertEquals(ErrorCodes.HEADER_MISMATCH, json(response).getJsonObject("error").getInt("code"));
    }

    @Test
    void nameHeaderMustMatchBodyAndMayBeBase64Encoded() {
        JsonObject args = Json.createObjectBuilder().add("name", "echo").add("arguments", Json.createObjectBuilder().add("message", "hi")).build();
        McpHttpResponse wrong = send(headers("tools/call", "other"), body("tools/call", "1", args));
        assertEquals(400, wrong.status());
        assertEquals(ErrorCodes.HEADER_MISMATCH, json(wrong).getJsonObject("error").getInt("code"));

        String encoded = "=?base64?" + Base64.getEncoder().encodeToString("echo".getBytes(StandardCharsets.UTF_8)) + "?=";
        McpHttpResponse right = send(headers("tools/call", encoded), body("tools/call", "1", args));
        assertEquals(200, right.status());
    }

    @Test
    void missingNameHeaderOnToolsCallIsAHeaderMismatch() {
        JsonObject args = Json.createObjectBuilder().add("name", "echo").build();
        McpHttpResponse response = send(headers("tools/call", null), body("tools/call", "1", args));
        assertEquals(400, response.status());
        assertEquals(ErrorCodes.HEADER_MISMATCH, json(response).getJsonObject("error").getInt("code"));
    }

    // ---- tools/list ------------------------------------------------------------------------------------------------

    @Test
    void toolsListIsSortedAndCarriesCacheHints() {
        McpHttpResponse response = post("tools/list", "1", null, null);
        assertEquals(200, response.status());
        JsonObject result = json(response).getJsonObject("result");
        assertEquals("complete", result.getString("resultType"));
        assertEquals(List.of("ask", "boom", "echo"), result.getJsonArray("tools").stream().map(t -> t.asJsonObject().getString("name")).toList());
        assertEquals("public", result.getString("cacheScope"));
        assertEquals(5_000, result.getJsonNumber("ttlMs").longValue());
        assertNull(result.get("nextCursor"));
    }

    @Test
    void anyCursorIsInvalidBecauseListsAreNeverPaged() {
        McpHttpResponse response = post("tools/list", "1", Json.createObjectBuilder().add("cursor", "abc").build(), null);
        assertEquals(200, response.status());
        assertEquals(ErrorCodes.INVALID_PARAMS, json(response).getJsonObject("error").getInt("code"));
    }

    // ---- tools/call ------------------------------------------------------------------------------------------------

    @Test
    void toolsCallReturnsContentAndResultType() {
        McpHttpResponse response = call("echo", Json.createObjectBuilder().add("message", "hello").build());
        assertEquals(200, response.status());
        JsonObject result = json(response).getJsonObject("result");
        assertEquals("complete", result.getString("resultType"));
        assertFalse(result.getBoolean("isError"));
        assertEquals("hello", result.getJsonArray("content").getJsonObject(0).getString("text"));
    }

    @Test
    void unknownToolIsInvalidParams() {
        McpHttpResponse response = call("nope", null);
        assertEquals(200, response.status());
        JsonObject error = json(response).getJsonObject("error");
        assertEquals(ErrorCodes.INVALID_PARAMS, error.getInt("code"));
        assertTrue(error.getString("message").startsWith("Unknown tool"));
    }

    @Test
    void missingRequiredArgumentIsInvalidParams() {
        McpHttpResponse response = call("echo", Json.createObjectBuilder().build());
        assertEquals(ErrorCodes.INVALID_PARAMS, json(response).getJsonObject("error").getInt("code"));
    }

    @Test
    void wrongArgumentTypeIsInvalidParams() {
        McpHttpResponse response = call("echo", Json.createObjectBuilder().add("message", 5).build());
        assertEquals(ErrorCodes.INVALID_PARAMS, json(response).getJsonObject("error").getInt("code"));
    }

    @Test
    void toolExceptionsBecomeGenericIsErrorResultsWithoutLeakingDetails() {
        McpHttpResponse response = call("boom", null);
        assertEquals(200, response.status());
        JsonObject result = json(response).getJsonObject("result");
        assertTrue(result.getBoolean("isError"));
        String text = result.getJsonArray("content").getJsonObject(0).getString("text");
        assertEquals("Tool execution failed", text);
        assertFalse(response.body().contains("secret detail"));
    }

    @Test
    void inputRequiredIsReturnedAndTheRetryCarriesTheAnswer() {
        McpHttpResponse first = call("ask", null);
        JsonObject firstResult = json(first).getJsonObject("result");
        assertEquals("input_required", firstResult.getString("resultType"));
        assertTrue(firstResult.getJsonObject("inputRequests").containsKey("q"));

        JsonObject answers = Json.createObjectBuilder().add("q", Json.createObjectBuilder().add("action", "accept")).build();
        McpHttpResponse second = post("tools/call", "2", Json.createObjectBuilder().add("name", "ask").add("inputResponses", answers).build(), "ask");
        JsonObject secondResult = json(second).getJsonObject("result");
        assertEquals("complete", secondResult.getString("resultType"));
        assertEquals("got it", secondResult.getJsonArray("content").getJsonObject(0).getString("text"));
    }

    // ---- transport -------------------------------------------------------------------------------------------------

    @Test
    void notificationsAreAcceptedWithoutBody() {
        String raw = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/whatever\"}";
        McpHttpResponse response = send(headers("notifications/whatever", null), raw);
        assertEquals(202, response.status());
        assertNull(response.body());
    }

    @Test
    void clientResponsesAreRejected() {
        McpHttpResponse response = send(headers("x", null), "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}");
        assertEquals(400, response.status());
        assertEquals(ErrorCodes.INVALID_REQUEST, json(response).getJsonObject("error").getInt("code"));
    }

    @Test
    void unknownMethodIsA404MethodNotFound() {
        McpHttpResponse response = post("ping", "1", null, null);
        assertEquals(404, response.status());
        assertEquals(ErrorCodes.METHOD_NOT_FOUND, json(response).getJsonObject("error").getInt("code"));
    }

    @Test
    void invalidJsonIsAParseErrorWithoutId() {
        McpHttpResponse response = send(headers("tools/list", null), "{not json");
        assertEquals(400, response.status());
        JsonObject body = json(response);
        assertEquals(ErrorCodes.PARSE_ERROR, body.getJsonObject("error").getInt("code"));
        assertFalse(body.containsKey("id"));
    }

    @Test
    void batchesAreRejected() {
        McpHttpResponse response = send(headers("tools/list", null), "[]");
        assertEquals(400, response.status());
        assertEquals(ErrorCodes.INVALID_REQUEST, json(response).getJsonObject("error").getInt("code"));
    }

    @Test
    void invalidIdIsRejected() {
        McpHttpResponse response = send(headers("tools/list", null), "{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"tools/list\"}");
        assertEquals(400, response.status());
        assertEquals(ErrorCodes.INVALID_REQUEST, json(response).getJsonObject("error").getInt("code"));
    }

    @Test
    void oversizedBodiesAreRejectedWith413() {
        String padding = "x".repeat(2000);
        McpHttpResponse response = send(headers("tools/list", null), "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"pad\":\"" + padding + "\"}");
        assertEquals(413, response.status());
    }

    @Test
    void browserOriginsAreRejectedUnlessConfigured() {
        Map<String, String> headers = headers("server/discover", null);
        headers.put("Origin", "https://evil.example");
        assertEquals(403, send(headers, body("server/discover", "1", null)).status());

        McpConfig allowing = new McpConfig("https://good.example/", 1024, 1000L, null, null, null);
        McpRequestHandler permissive = new McpRequestHandler(McpRegistry.of(List.of()), allowing);
        headers.put("Origin", "https://good.example");
        assertEquals(200, permissive.handle(headers::get, new ByteArrayInputStream(body("server/discover", "1", null).getBytes(StandardCharsets.UTF_8))).status());
    }

    @Test
    void loopbackOriginsAreAllowedByDefaultAndLookalikesAreNot() {
        for (String origin : List.of("http://localhost:8080", "http://localhost", "https://localhost:3000", "http://127.0.0.1:6274", "http://[::1]:8080", "HTTP://LOCALHOST:8080/")) {
            Map<String, String> headers = headers("server/discover", null);
            headers.put("Origin", origin);
            assertEquals(200, send(headers, body("server/discover", "1", null)).status(), origin);
        }
        for (String origin : List.of("http://localhost.evil.example", "http://evil.example", "http://127.0.0.1.evil.example", "http://localhost:80@evil.example", "http://evil.example/localhost", "http://localhost:99999x", "null", "ftp://localhost")) {
            Map<String, String> headers = headers("server/discover", null);
            headers.put("Origin", origin);
            assertEquals(403, send(headers, body("server/discover", "1", null)).status(), origin);
        }
    }

    @Test
    void sessionHeadersAreIgnoredAndNeverEchoed() {
        Map<String, String> headers = headers("server/discover", null);
        headers.put("Mcp-Session-Id", "abc");
        McpHttpResponse response = send(headers, body("server/discover", "1", null));
        assertEquals(200, response.status());
        assertFalse(response.body().contains("abc"));
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private McpHttpResponse call(String tool, JsonObject arguments) {
        JsonObjectBuilder params = Json.createObjectBuilder().add("name", tool);
        if (arguments != null) {
            params.add("arguments", arguments);
        }
        return post("tools/call", "1", params.build(), tool);
    }

    private McpHttpResponse post(String method, String id, JsonObject extraParams, String name) {
        return send(headers(method, name), body(method, id, extraParams));
    }

    private McpHttpResponse send(Map<String, String> headers, String raw) {
        return handler.handle(headers::get, new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
    }

    private static Map<String, String> headers(String method, String name) {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.put("MCP-Protocol-Version", VERSION);
        headers.put("Mcp-Method", method);
        if (name != null) {
            headers.put("Mcp-Name", name);
        }
        return headers;
    }

    private static String body(String method, String id, JsonObject extraParams) {
        JsonObjectBuilder params = extraParams == null ? Json.createObjectBuilder() : Json.createObjectBuilder(extraParams);
        JsonObject meta = Json.createObjectBuilder().add("io.modelcontextprotocol/protocolVersion", VERSION).add("io.modelcontextprotocol/clientInfo", Json.createObjectBuilder().add("name", "test-client").add("version", "0")).add("io.modelcontextprotocol/clientCapabilities", Json.createObjectBuilder().add("elicitation", Json.createObjectBuilder())).build();
        params.add("_meta", meta);
        JsonObjectBuilder message = Json.createObjectBuilder().add("jsonrpc", "2.0");
        if (id.chars().allMatch(Character::isDigit)) {
            message.add("id", Integer.parseInt(id));
        } else {
            message.add("id", id);
        }
        return message.add("method", method).add("params", params).build().toString();
    }

    private static JsonObject json(McpHttpResponse response) {
        return Json.createReader(new StringReader(response.body())).readObject();
    }

    // ---- test tools ------------------------------------------------------------------------------------------------

    private static final class EchoTool implements McpToolHandler {

        @Override
        public String name() {
            return "echo";
        }

        @Override
        public String description() {
            return "echo";
        }

        @Override
        public JsonObject inputSchema() {
            return Json.createObjectBuilder().add("type", "object").add("properties", Json.createObjectBuilder().add("message", Json.createObjectBuilder().add("type", "string"))).add("required", Json.createArrayBuilder().add("message")).add("additionalProperties", false).build();
        }

        @Override
        public ToolResult call(ToolCall call) {
            return ToolResult.text(call.stringArgument("message").orElse(""));
        }
    }

    private static final class BoomTool implements McpToolHandler {

        @Override
        public String name() {
            return "boom";
        }

        @Override
        public String description() {
            return null;
        }

        @Override
        public JsonObject inputSchema() {
            return Json.createObjectBuilder().add("type", "object").build();
        }

        @Override
        public ToolResult call(ToolCall call) {
            throw new IllegalStateException("secret detail");
        }
    }

    private static final class AskTool implements McpToolHandler {

        @Override
        public String name() {
            return "ask";
        }

        @Override
        public String description() {
            return "asks first";
        }

        @Override
        public JsonObject inputSchema() {
            return Json.createObjectBuilder().add("type", "object").build();
        }

        @Override
        public ToolResult call(ToolCall call) {
            if (call.inputResponse("q").isPresent()) {
                return ToolResult.text("got it");
            }
            if (!call.clientSupports("elicitation")) {
                throw McpProtocolException.missingClientCapability("elicitation");
            }
            return ToolResult.inputRequired(Json.createObjectBuilder().add("q", Json.createObjectBuilder().add("method", "elicitation/create")).build(), null);
        }
    }
}
