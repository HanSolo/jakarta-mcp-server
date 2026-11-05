package eu.hansolo.mcp.server;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import eu.hansolo.mcp.api.Caller;
import eu.hansolo.mcp.api.McpPromptHandler;
import eu.hansolo.mcp.api.McpResourceHandler;
import eu.hansolo.mcp.api.McpResourceTemplateHandler;
import eu.hansolo.mcp.api.McpToolHandler;
import eu.hansolo.mcp.api.PromptArgument;
import eu.hansolo.mcp.api.PromptCall;
import eu.hansolo.mcp.api.PromptMessage;
import eu.hansolo.mcp.api.PromptResult;
import eu.hansolo.mcp.api.ResourceCall;
import eu.hansolo.mcp.api.ResourceContent;
import eu.hansolo.mcp.api.ResourceResult;
import eu.hansolo.mcp.api.ToolCall;
import eu.hansolo.mcp.api.ToolResult;
import eu.hansolo.mcp.auth.AuthConfig;
import eu.hansolo.mcp.auth.McpAuthenticator;
import eu.hansolo.mcp.auth.TokenException;
import eu.hansolo.mcp.protocol.ErrorCodes;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpResourcesPromptsTest {

    private static final String VERSION = "2026-07-28";

    private final McpConfig         config  = new McpConfig(null, 4096, 5_000L, "t", "1", null);
    private final McpRegistry       full    = McpRegistry.of(List.of(), List.of(new Info(), new Secret(), new Counter()), List.of(new Greeting(), new Docs()), List.of(new Summarize()));
    private final McpRequestHandler handler = new McpRequestHandler(full, config);

    // ---- discovery and absent features ---------------------------------------------------------------------------

    @Test
    void discoverAdvertisesResourcesAndPromptsOnlyWhenPresent() {
        JsonObject capabilities = result(send("server/discover", null, null)).getJsonObject("capabilities");
        assertTrue(capabilities.containsKey("resources"));
        assertTrue(capabilities.containsKey("prompts"));
        McpRequestHandler toolsOnly = new McpRequestHandler(McpRegistry.of(List.of(new NoopTool())), config);
        JsonObject bare = result(send(toolsOnly, "server/discover", null, null)).getJsonObject("capabilities");
        assertFalse(bare.containsKey("resources"));
        assertFalse(bare.containsKey("prompts"));
    }

    @Test
    void methodsOfFeaturesThatAreNotRegisteredAreNotFound() {
        McpRequestHandler toolsOnly = new McpRequestHandler(McpRegistry.of(List.of(new NoopTool())), config);
        for (String method : List.of("resources/list", "resources/templates/list", "prompts/list")) {
            McpHttpResponse response = send(toolsOnly, method, null, null);
            assertEquals(404, response.status(), method);
            assertEquals(ErrorCodes.METHOD_NOT_FOUND, json(response).getJsonObject("error").getInt("code"));
        }
    }

    // ---- resources/list and templates --------------------------------------------------------------------------

    @Test
    void listsFixedResourcesWithTheirMetadataAndCachingHints() {
        JsonObject result = result(send("resources/list", null, null));
        assertEquals("complete", result.getString("resultType"));
        assertEquals(5_000, result.getJsonNumber("ttlMs").longValue());
        assertEquals("public", result.getString("cacheScope"));
        JsonArray resources = result.getJsonArray("resources");
        assertEquals(List.of("config://info", "counter://now", "secret://x"), resources.getValuesAs(JsonObject.class).stream().map(r -> r.getString("uri")).toList());
        JsonObject info = resources.getJsonObject(0);
        assertEquals("info", info.getString("name"));
        assertEquals("application/json", info.getString("mimeType"));
        assertEquals(12, info.getInt("size"));
    }

    @Test
    void listsTemplates() {
        JsonArray templates = result(send("resources/templates/list", null, null)).getJsonArray("resourceTemplates");
        assertEquals(List.of("docs://{+path}", "greeting://{name}"), templates.getValuesAs(JsonObject.class).stream().map(t -> t.getString("uriTemplate")).toList());
    }

    @Test
    void anyCursorIsRejectedBecauseNothingIsPaginated() {
        for (String method : List.of("resources/list", "resources/templates/list", "prompts/list", "tools/list")) {
            JsonObject error = json(send(method, Json.createObjectBuilder().add("cursor", "abc").build(), null)).getJsonObject("error");
            assertEquals(ErrorCodes.INVALID_PARAMS, error.getInt("code"), method);
        }
    }

    // ---- resources/read --------------------------------------------------------------------------------------

    @Test
    void readsAFixedResourceWithDefaultCaching() {
        JsonObject result = result(read("config://info"));
        assertEquals("complete", result.getString("resultType"));
        JsonObject content = result.getJsonArray("contents").getJsonObject(0);
        assertEquals("config://info", content.getString("uri"));
        assertEquals("{\"a\":true}", content.getString("text"));
        assertEquals(0, result.getJsonNumber("ttlMs").longValue());
        assertEquals("public", result.getString("cacheScope"));
    }

    @Test
    void aHandlerCanOverrideCachingHints() {
        JsonObject result = result(read("counter://now"));
        assertEquals(60_000, result.getJsonNumber("ttlMs").longValue());
        assertEquals("private", result.getString("cacheScope"));
    }

    @Test
    void readsTemplatedResourcesWithDecodedVariables() {
        assertEquals("Hello, Ann Lee!", result(read("greeting://Ann%20Lee")).getJsonArray("contents").getJsonObject(0).getString("text"));
        assertEquals("a/b/c", result(read("docs://a/b/c")).getJsonArray("contents").getJsonObject(0).getString("text"));
    }

    @Test
    void blobContentIsReturnedAsBase64() {
        JsonObject content = result(read("secret://x")).getJsonArray("contents").getJsonObject(0);
        assertEquals("AQID", content.getString("blob"));
        assertFalse(content.containsKey("text"));
    }

    @Test
    void anUnknownResourceIsInvalidParamsWithTheUriAndNeverAnEmptyContentsList() {
        McpHttpResponse response = read("config://missing");
        assertEquals(200, response.status());
        JsonObject error = json(response).getJsonObject("error");
        assertEquals(ErrorCodes.INVALID_PARAMS, error.getInt("code"));
        assertEquals("config://missing", error.getJsonObject("data").getString("uri"));
        assertFalse(json(response).containsKey("result"));
    }

    @Test
    void aTemplateThatDoesNotMatchIsNotFound() {
        assertEquals(ErrorCodes.INVALID_PARAMS, json(read("greeting://a/b")).getJsonObject("error").getInt("code"));
    }

    @Test
    void relativeAndOversizedUrisAreInvalid() {
        assertEquals("Invalid resource URI", json(read("no-scheme")).getJsonObject("error").getString("message"));
        assertEquals("Invalid resource URI", json(read("x://" + "a".repeat(3000))).getJsonObject("error").getString("message"));
    }

    @Test
    void theNameHeaderMustMatchTheUri() {
        Map<String, String> headers = headers("resources/read", "config://other");
        McpHttpResponse response = handler.handle(headers::get, stream(body("resources/read", Json.createObjectBuilder().add("uri", "config://info").build())));
        assertEquals(400, response.status());
        assertEquals(ErrorCodes.HEADER_MISMATCH, json(response).getJsonObject("error").getInt("code"));
    }

    @Test
    void aHandlerFailureIsAnInternalErrorWithoutDetails() {
        McpRegistry registry = McpRegistry.of(List.of(), List.of(new Failing()), List.of(), List.of());
        McpHttpResponse response = send(new McpRequestHandler(registry, config), "resources/read", Json.createObjectBuilder().add("uri", "boom://x").build(), "boom://x");
        assertEquals(500, response.status());
        assertFalse(response.body().contains("secret detail"));
    }

    @Test
    void inputRequiredCarriesNoCachingHints() {
        JsonObject result = result(read("ask://x", new Asking()));
        assertEquals("input_required", result.getString("resultType"));
        assertEquals("state-1", result.getString("requestState"));
        assertFalse(result.containsKey("ttlMs"));
        assertFalse(result.containsKey("cacheScope"));
        assertFalse(result.containsKey("contents"));
    }

    // ---- prompts ---------------------------------------------------------------------------------------------

    @Test
    void listsPromptsWithArguments() {
        JsonArray prompts = result(send("prompts/list", null, null)).getJsonArray("prompts");
        assertEquals(List.of("summarize"), prompts.getValuesAs(JsonObject.class).stream().map(p -> p.getString("name")).toList());
        JsonArray arguments = prompts.getJsonObject(0).getJsonArray("arguments");
        assertEquals("text", arguments.getJsonObject(0).getString("name"));
        assertTrue(arguments.getJsonObject(0).getBoolean("required"));
        assertFalse(arguments.getJsonObject(1).getBoolean("required"));
    }

    @Test
    void getsAPromptAndHasNoCacheHints() {
        JsonObject result = result(getPrompt("summarize", Json.createObjectBuilder().add("text", "abc").add("style", "short").build()));
        assertEquals("complete", result.getString("resultType"));
        JsonObject message = result.getJsonArray("messages").getJsonObject(0);
        assertEquals("user", message.getString("role"));
        assertEquals("text", message.getJsonObject("content").getString("type"));
        assertEquals("Summarize abc in short", message.getJsonObject("content").getString("text"));
        assertFalse(result.containsKey("ttlMs"));
    }

    @Test
    void optionalPromptArgumentsMayBeOmitted() {
        JsonObject result = result(getPrompt("summarize", Json.createObjectBuilder().add("text", "abc").build()));
        assertEquals("Summarize abc in default", result.getJsonArray("messages").getJsonObject(0).getJsonObject("content").getString("text"));
    }

    @Test
    void promptArgumentProblemsAreInvalidParams() {
        assertEquals(ErrorCodes.INVALID_PARAMS, json(getPrompt("summarize", null)).getJsonObject("error").getInt("code"));
        assertTrue(json(getPrompt("summarize", null)).getJsonObject("error").getString("message").contains("text"));
        assertEquals(ErrorCodes.INVALID_PARAMS, json(getPrompt("summarize", Json.createObjectBuilder().add("text", "a").add("extra", "b").build())).getJsonObject("error").getInt("code"));
        assertEquals(ErrorCodes.INVALID_PARAMS, json(getPrompt("summarize", Json.createObjectBuilder().add("text", 5).build())).getJsonObject("error").getInt("code"));
        assertEquals(ErrorCodes.INVALID_PARAMS, json(getPrompt("nope", null)).getJsonObject("error").getInt("code"));
    }

    // ---- scopes ----------------------------------------------------------------------------------------------

    @Test
    void listsAreFilteredByScopeAndSharedCachingIsDisabledWhenTheyVary() {
        McpAuthenticator authenticator = header -> {
            if (header == null) {
                throw new TokenException(null);
            }
            return new Caller("u", header.equals("Bearer admin") ? Set.of("mcp:tools", "admin") : Set.of("mcp:tools"), Json.createObjectBuilder().build());
        };
        AuthConfig auth = new AuthConfig(true, "https://mcp.example.com/mcp", "https://auth.example.com", "https://auth.example.com/jwks", null, List.of(), List.of("mcp:tools", "admin"), List.of("mcp:tools"), 60, 300, 30, false);
        McpRequestHandler secured = new McpRequestHandler(McpRegistry.of(List.of(), List.of(new Info(), new Admin()), List.of(), List.of(new Restricted())), config, auth, authenticator);

        JsonObject basic = result(send(secured, "Bearer user", "resources/list", null, null));
        assertEquals(List.of("config://info"), basic.getJsonArray("resources").getValuesAs(JsonObject.class).stream().map(r -> r.getString("uri")).toList());
        assertEquals("private", basic.getString("cacheScope"));
        assertEquals(0, result(send(secured, "Bearer user", "prompts/list", null, null)).getJsonArray("prompts").size());

        JsonObject admin = result(send(secured, "Bearer admin", "resources/list", null, null));
        assertEquals(2, admin.getJsonArray("resources").size());
        assertEquals(1, result(send(secured, "Bearer admin", "prompts/list", null, null)).getJsonArray("prompts").size());
    }

    @Test
    void readingOrGettingWithoutTheScopeIsA403WithAChallenge() {
        McpAuthenticator authenticator = header -> new Caller("u", Set.of("mcp:tools"), Json.createObjectBuilder().build());
        AuthConfig auth = new AuthConfig(true, "https://mcp.example.com/mcp", "https://auth.example.com", "https://auth.example.com/jwks", null, List.of(), List.of("mcp:tools", "admin"), List.of("mcp:tools"), 60, 300, 30, false);
        McpRequestHandler secured = new McpRequestHandler(McpRegistry.of(List.of(), List.of(new Admin()), List.of(), List.of(new Restricted())), config, auth, authenticator);

        McpHttpResponse read = send(secured, "Bearer x", "resources/read", Json.createObjectBuilder().add("uri", "admin://x").build(), "admin://x");
        assertEquals(403, read.status());
        assertTrue(read.headers().get("WWW-Authenticate").contains("scope=\"admin\""));
        McpHttpResponse get = send(secured, "Bearer x", "prompts/get", Json.createObjectBuilder().add("name", "restricted").build(), "restricted");
        assertEquals(403, get.status());
    }

    @Test
    void registryRejectsInvalidDefinitions() {
        assertThrows(IllegalStateException.class, () -> McpRegistry.of(List.of(), List.of(new Info(), new Info()), List.of(), List.of()));
        assertThrows(IllegalStateException.class, () -> McpRegistry.of(List.of(), List.of(new Named("not a uri")), List.of(), List.of()));
        assertThrows(IllegalStateException.class, () -> McpRegistry.of(List.of(), List.of(), List.of(), List.of(new Summarize(), new Summarize())));
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private McpHttpResponse read(String uri) {
        return send("resources/read", Json.createObjectBuilder().add("uri", uri).build(), uri);
    }

    private McpHttpResponse read(String uri, McpResourceHandler extra) {
        McpRegistry registry = McpRegistry.of(List.of(), List.of(extra), List.of(), List.of());
        return send(new McpRequestHandler(registry, config), "resources/read", Json.createObjectBuilder().add("uri", uri).build(), uri);
    }

    private McpHttpResponse getPrompt(String name, JsonObject arguments) {
        JsonObjectBuilder params = Json.createObjectBuilder().add("name", name);
        if (arguments != null) {
            params.add("arguments", arguments);
        }
        return send("prompts/get", params.build(), name);
    }

    private McpHttpResponse send(String method, JsonObject params, String name) {
        return send(handler, method, params, name);
    }

    private McpHttpResponse send(McpRequestHandler target, String method, JsonObject params, String name) {
        return send(target, null, method, params, name);
    }

    private McpHttpResponse send(McpRequestHandler target, String authorization, String method, JsonObject params, String name) {
        Map<String, String> headers = headers(method, name);
        if (authorization != null) {
            headers.put("Authorization", authorization);
        }
        return target.handle(headers::get, stream(body(method, params)));
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

    private static String body(String method, JsonObject extraParams) {
        JsonObjectBuilder params = extraParams == null ? Json.createObjectBuilder() : Json.createObjectBuilder(extraParams);
        JsonObject meta = Json.createObjectBuilder().add("io.modelcontextprotocol/protocolVersion", VERSION).add("io.modelcontextprotocol/clientInfo", Json.createObjectBuilder().add("name", "c").add("version", "0")).add("io.modelcontextprotocol/clientCapabilities", Json.createObjectBuilder()).build();
        return Json.createObjectBuilder().add("jsonrpc", "2.0").add("id", 1).add("method", method).add("params", params.add("_meta", meta)).build().toString();
    }

    private static ByteArrayInputStream stream(String raw) {
        return new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static JsonObject json(McpHttpResponse response) {
        return Json.createReader(new StringReader(response.body())).readObject();
    }

    private static JsonObject result(McpHttpResponse response) {
        assertEquals(200, response.status(), response.body());
        return json(response).getJsonObject("result");
    }


    // ---- fakes -----------------------------------------------------------------------------------------------

    private static class Named implements McpResourceHandler {

        private final String uri;


        Named(String uri) {
            this.uri = uri;
        }


        @Override
        public String uri() {
            return uri;
        }

        @Override
        public String name() {
            return "n";
        }

        @Override
        public ResourceResult read(ResourceCall call) {
            return ResourceResult.text(call.uri(), "text/plain", "x");
        }
    }


    private static final class Info extends Named {

        Info() {
            super("config://info");
        }


        @Override
        public String name() {
            return "info";
        }

        @Override
        public String mimeType() {
            return "application/json";
        }

        @Override
        public Long size() {
            return 12L;
        }

        @Override
        public ResourceResult read(ResourceCall call) {
            return ResourceResult.text(call.uri(), "application/json", "{\"a\":true}");
        }
    }


    private static final class Secret extends Named {

        Secret() {
            super("secret://x");
        }


        @Override
        public ResourceResult read(ResourceCall call) {
            return ResourceResult.of(ResourceContent.blob(call.uri(), "application/octet-stream", new byte[]{1, 2, 3}));
        }
    }


    private static final class Counter extends Named {

        Counter() {
            super("counter://now");
        }


        @Override
        public ResourceResult read(ResourceCall call) {
            return ResourceResult.text(call.uri(), "text/plain", "1").withCache(60_000, "private");
        }
    }


    private static final class Admin extends Named {

        Admin() {
            super("admin://x");
        }


        @Override
        public Set<String> requiredScopes() {
            return Set.of("admin");
        }
    }


    private static final class Failing extends Named {

        Failing() {
            super("boom://x");
        }


        @Override
        public ResourceResult read(ResourceCall call) {
            throw new IllegalStateException("secret detail");
        }
    }


    private static final class Asking extends Named {

        Asking() {
            super("ask://x");
        }


        @Override
        public ResourceResult read(ResourceCall call) {
            return ResourceResult.inputRequired(Json.createObjectBuilder().add("q", Json.createObjectBuilder().add("method", "elicitation/create")).build(), "state-1");
        }
    }


    private static final class Greeting implements McpResourceTemplateHandler {

        @Override
        public String uriTemplate() {
            return "greeting://{name}";
        }

        @Override
        public String name() {
            return "greeting";
        }

        @Override
        public ResourceResult read(ResourceCall call) {
            return ResourceResult.text(call.uri(), "text/plain", "Hello, " + call.variables().get("name") + "!");
        }
    }


    private static final class Docs implements McpResourceTemplateHandler {

        @Override
        public String uriTemplate() {
            return "docs://{+path}";
        }

        @Override
        public String name() {
            return "docs";
        }

        @Override
        public ResourceResult read(ResourceCall call) {
            return ResourceResult.text(call.uri(), "text/plain", call.variables().get("path"));
        }
    }


    private static class Summarize implements McpPromptHandler {

        @Override
        public String name() {
            return "summarize";
        }

        @Override
        public List<PromptArgument> arguments() {
            return List.of(PromptArgument.required("text", "The text"), PromptArgument.optional("style", "The style"));
        }

        @Override
        public PromptResult get(PromptCall call) {
            return PromptResult.of(PromptMessage.user("Summarize " + call.arguments().get("text") + " in " + call.argument("style").orElse("default")));
        }
    }


    private static final class Restricted implements McpPromptHandler {

        @Override
        public String name() {
            return "restricted";
        }

        @Override
        public Set<String> requiredScopes() {
            return Set.of("admin");
        }

        @Override
        public PromptResult get(PromptCall call) {
            return PromptResult.of(PromptMessage.user("secret"));
        }
    }


    private static final class NoopTool implements McpToolHandler {

        @Override
        public String name() {
            return "noop";
        }

        @Override
        public String description() {
            return "does nothing";
        }

        @Override
        public JsonObject inputSchema() {
            return Json.createObjectBuilder().add("type", "object").build();
        }

        @Override
        public ToolResult call(ToolCall call) {
            return ToolResult.text("ok");
        }
    }
}
