package eu.hansolo.mcp.server;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import eu.hansolo.mcp.api.Caller;
import eu.hansolo.mcp.api.McpToolHandler;
import eu.hansolo.mcp.api.ToolCall;
import eu.hansolo.mcp.api.ToolResult;
import eu.hansolo.mcp.auth.AuthConfig;
import eu.hansolo.mcp.auth.McpAuthenticator;
import eu.hansolo.mcp.auth.TokenException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpRequestHandlerAuthTest {

    private static final String METADATA = "https://mcp.example.com/.well-known/oauth-protected-resource/mcp";

    /** Tokens are just names here: "reader" has files:read, "writer" has files, "base" has only mcp:tools, anything else is rejected. */
    private final McpAuthenticator authenticator = header -> {
        if (header == null) {
            throw new TokenException(null);
        }
        return switch (header) {
            case "Bearer reader" -> new Caller("alice", Set.of("mcp:tools", "files:read"), JsonObjectOf("alice"));
            case "Bearer writer" -> new Caller("bob", Set.of("mcp:tools", "files"), JsonObjectOf("bob"));
            case "Bearer base" -> new Caller("carol", Set.of("mcp:tools"), JsonObjectOf("carol"));
            case "Bearer nothing" -> new Caller("dave", Set.of(), JsonObjectOf("dave"));
            default -> throw new TokenException("Token expired");
        };
    };

    private final AuthConfig config = new AuthConfig(true, "https://mcp.example.com/mcp", "https://auth.example.com", "https://auth.example.com/jwks", null, List.of(), List.of("mcp:tools", "files"), List.of("mcp:tools"), 60, 300, 30, false);
    private final McpConfig mcpConfig = new McpConfig(null, 4096, 1000L, "t", "1", null);
    private final McpRequestHandler handler = new McpRequestHandler(McpRegistry.of(List.of(new PlainTool(), new ReadTool(), new WriteTool())), mcpConfig, config, authenticator);

    private static JsonObject JsonObjectOf(String sub) {
        return Json.createObjectBuilder().add("sub", sub).build();
    }

    @Test
    void missingCredentialsGetA401WithMetadataAndScopeHint() {
        McpHttpResponse response = send(null, "tools/list", null);
        assertEquals(401, response.status());
        assertEquals("Bearer resource_metadata=\"" + METADATA + "\", scope=\"mcp:tools files\"", response.headers().get("WWW-Authenticate"));
        assertFalse(response.headers().get("WWW-Authenticate").contains("error="));
    }

    @Test
    void rejectedTokensGetInvalidTokenInTheChallenge() {
        McpHttpResponse response = send("Bearer garbage", "tools/list", null);
        assertEquals(401, response.status());
        String challenge = response.headers().get("WWW-Authenticate");
        assertTrue(challenge.startsWith("Bearer error=\"invalid_token\", error_description=\"Token expired\""), challenge);
        assertTrue(challenge.contains("resource_metadata=\"" + METADATA + "\""));
    }

    @Test
    void authenticationHappensBeforeTheBodyIsEvenParsed() {
        McpHttpResponse response = handler.handle(new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)::get, new ByteArrayInputStream("not json".getBytes(StandardCharsets.UTF_8)));
        assertEquals(401, response.status());
    }

    @Test
    void globallyRequiredScopeIsEnforcedWith403() {
        McpHttpResponse response = send("Bearer nothing", "tools/list", null);
        assertEquals(403, response.status());
        assertEquals("Bearer error=\"insufficient_scope\", scope=\"mcp:tools\", resource_metadata=\"" + METADATA + "\"", response.headers().get("WWW-Authenticate"));
    }

    @Test
    void validTokensCanListAndCallUnscopedTools() {
        assertEquals(200, send("Bearer base", "tools/list", null).status());
        McpHttpResponse call = send("Bearer base", "tools/call", "plain");
        assertEquals(200, call.status());
        assertEquals("carol", json(call).getJsonObject("result").getJsonArray("content").getJsonObject(0).getString("text"));
    }

    @Test
    void toolScopesAreCheckedPerCallWith403AndTheNeededScope() {
        McpHttpResponse denied = send("Bearer base", "tools/call", "read");
        assertEquals(403, denied.status());
        assertTrue(denied.headers().get("WWW-Authenticate").contains("error=\"insufficient_scope\", scope=\"files:read\""));
        assertEquals(200, send("Bearer reader", "tools/call", "read").status());
    }

    @Test
    void broaderScopeSatisfiesNarrowerToolRequirementButNotTheOtherWayAround() {
        assertEquals(200, send("Bearer writer", "tools/call", "read").status());
        assertEquals(403, send("Bearer reader", "tools/call", "write").status());
        assertEquals(200, send("Bearer writer", "tools/call", "write").status());
    }

    @Test
    void scopeIsCheckedBeforeArgumentValidationSoSchemasDoNotLeakToUnauthorizedCallers() {
        McpHttpResponse denied = sendRaw("Bearer base", "tools/call", "write", Json.createObjectBuilder().add("name", "write").add("arguments", Json.createObjectBuilder().add("junk", 1)).build());
        assertEquals(403, denied.status());
    }

    @Test
    void disabledAuthenticationNeverAsksForCredentials() {
        McpRequestHandler open = new McpRequestHandler(McpRegistry.of(List.of(new PlainTool())), mcpConfig);
        Map<String, String> headers = headers("tools/call", "plain");
        McpHttpResponse response = open.handle(headers::get, new ByteArrayInputStream(body("tools/call", Json.createObjectBuilder().add("name", "plain").build()).getBytes(StandardCharsets.UTF_8)));
        assertEquals(200, response.status());
        assertEquals("", json(response).getJsonObject("result").getJsonArray("content").getJsonObject(0).getString("text"));
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private McpHttpResponse send(String authorization, String method, String tool) {
        JsonObject params = tool == null ? null : Json.createObjectBuilder().add("name", tool).build();
        return sendRaw(authorization, method, tool, params);
    }

    private McpHttpResponse sendRaw(String authorization, String method, String tool, JsonObject params) {
        Map<String, String> headers = headers(method, tool);
        if (authorization != null) {
            headers.put("Authorization", authorization);
        }
        return handler.handle(headers::get, new ByteArrayInputStream(body(method, params).getBytes(StandardCharsets.UTF_8)));
    }

    private static Map<String, String> headers(String method, String tool) {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.put("MCP-Protocol-Version", "2026-07-28");
        headers.put("Mcp-Method", method);
        if (tool != null) {
            headers.put("Mcp-Name", tool);
        }
        return headers;
    }

    private static String body(String method, JsonObject extra) {
        var params = extra == null ? Json.createObjectBuilder() : Json.createObjectBuilder(extra);
        params.add("_meta", Json.createObjectBuilder().add("io.modelcontextprotocol/protocolVersion", "2026-07-28").add("io.modelcontextprotocol/clientCapabilities", Json.createObjectBuilder()));
        return Json.createObjectBuilder().add("jsonrpc", "2.0").add("id", 1).add("method", method).add("params", params).build().toString();
    }

    private static JsonObject json(McpHttpResponse response) {
        try (var reader = Json.createReader(new StringReader(response.body()))) {
            return reader.readObject();
        }
    }

    private abstract static class BaseTool implements McpToolHandler {

        private final String name;


        BaseTool(String name) {
            this.name = name;
        }


        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return name;
        }

        @Override
        public JsonObject inputSchema() {
            return Json.createObjectBuilder().add("type", "object").add("additionalProperties", false).build();
        }

        @Override
        public ToolResult call(ToolCall call) {
            return ToolResult.text(call.caller().subject());
        }
    }

    private static final class PlainTool extends BaseTool {

        PlainTool() {
            super("plain");
        }
    }

    private static final class ReadTool extends BaseTool {

        ReadTool() {
            super("read");
        }


        @Override
        public Set<String> requiredScopes() {
            return Set.of("files:read");
        }
    }

    private static final class WriteTool extends BaseTool {

        WriteTool() {
            super("write");
        }


        @Override
        public Set<String> requiredScopes() {
            return Set.of("files:write");
        }
    }
}
