package eu.hansolo.mcp.server;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import eu.hansolo.mcp.api.Caller;
import eu.hansolo.mcp.api.McpPromptHandler;
import eu.hansolo.mcp.api.McpResourceHandler;
import eu.hansolo.mcp.api.McpToolHandler;
import eu.hansolo.mcp.api.PromptCall;
import eu.hansolo.mcp.api.PromptMessage;
import eu.hansolo.mcp.api.PromptResult;
import eu.hansolo.mcp.api.ResourceCall;
import eu.hansolo.mcp.api.ResourceResult;
import eu.hansolo.mcp.api.ToolCall;
import eu.hansolo.mcp.api.ToolResult;
import eu.hansolo.mcp.auth.AuthConfig;
import eu.hansolo.mcp.protocol.ErrorCodes;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpPaginationTest {

    private static final String VERSION = "2026-07-28";

    // ---- tools -----------------------------------------------------------------------------------------------------

    @Test
    void aListLongerThanThePageSizeIsReturnedInPagesInOrder() {
        McpRequestHandler handler = tools(2, "e", "a", "d", "b", "c");
        JsonObject first = result(list(handler, "tools/list", null));
        assertEquals(List.of("a", "b"), names(first.getJsonArray("tools"), "name"));
        assertTrue(first.containsKey("nextCursor"));
        assertEquals(List.of("a", "b", "c", "d", "e"), walk(handler, "tools/list", "tools", "name"));
    }

    @Test
    void theLastPageHasNoCursorAndAnExactFitNeverIssuesOne() {
        JsonObject exact = result(list(tools(2, "a", "b"), "tools/list", null));
        assertEquals(2, exact.getJsonArray("tools").size());
        assertFalse(exact.containsKey("nextCursor"));
    }

    @Test
    void withoutAPageSizeEverythingComesBackAtOnceWithoutACursor() {
        JsonObject all = result(list(tools(0, "a", "b", "c"), "tools/list", null));
        assertEquals(3, all.getJsonArray("tools").size());
        assertFalse(all.containsKey("nextCursor"));
    }

    @Test
    void cursorsSurviveChangesToTheListAndOtherInstances() {
        String cursor = result(list(tools(2, "a", "b", "c", "d", "e"), "tools/list", null)).getString("nextCursor");
        JsonObject next = result(list(tools(2, "a", "bb", "d", "e"), "tools/list", cursor));
        assertEquals(List.of("bb", "d"), names(next.getJsonArray("tools"), "name"));
    }

    @Test
    void badCursorsAreInvalidParams() {
        McpRequestHandler handler = tools(2, "a", "b", "c");
        for (Object cursor : new Object[] {"%%%", "bm90LWEtY3Vyc29y", "", 5, true}) {
            JsonObjectBuilder params = Json.createObjectBuilder();
            if (cursor instanceof String s) {
                params.add("cursor", s);
            } else if (cursor instanceof Integer i) {
                params.add("cursor", i);
            } else {
                params.add("cursor", (Boolean) cursor);
            }
            JsonObject error = json(list(handler, "tools/list", params)).getJsonObject("error");
            assertEquals(ErrorCodes.INVALID_PARAMS, error.getInt("code"), String.valueOf(cursor));
        }
        String tooLong = Base64.getUrlEncoder().withoutPadding().encodeToString(("k1:" + "x".repeat(5000)).getBytes(StandardCharsets.UTF_8));
        assertEquals(ErrorCodes.INVALID_PARAMS, json(list(handler, "tools/list", Json.createObjectBuilder().add("cursor", tooLong))).getJsonObject("error").getInt("code"));
    }

    @Test
    void whenPaginationIsOffEveryCursorIsInvalid() {
        String cursor = Base64.getUrlEncoder().withoutPadding().encodeToString("k1:a".getBytes(StandardCharsets.UTF_8));
        assertEquals(ErrorCodes.INVALID_PARAMS, json(list(tools(0, "a", "b"), "tools/list", Json.createObjectBuilder().add("cursor", cursor))).getJsonObject("error").getInt("code"));
    }

    // ---- other lists and scopes ------------------------------------------------------------------------------------

    @Test
    void resourcesAndPromptsPageToo() {
        McpRegistry registry = McpRegistry.of(List.of(), List.of(new Res("a://3"), new Res("a://1"), new Res("a://2")), List.of(), List.of(new Prompt("p2", Set.of()), new Prompt("p1", Set.of()), new Prompt("p3", Set.of())));
        McpRequestHandler handler = new McpRequestHandler(registry, new McpConfig(null, 4096, 1000L, "t", "1", null, 2));
        assertEquals(List.of("a://1", "a://2", "a://3"), walk(handler, "resources/list", "resources", "uri"));
        assertEquals(List.of("p1", "p2", "p3"), walk(handler, "prompts/list", "prompts", "name"));
    }

    @Test
    void pagesContainOnlyWhatTheCallerMayAndKeepTheSameCacheScope() {
        AuthConfig auth = new AuthConfig(true, "https://mcp.example.com/mcp", "https://auth.example.com", "https://auth.example.com/jwks", null, List.of(), List.of("mcp:tools", "admin"), List.of("mcp:tools"), 60, 300, 30, false);
        McpRegistry registry = McpRegistry.of(List.of(), List.of(), List.of(), List.of(new Prompt("a", Set.of("admin")), new Prompt("b", Set.of()), new Prompt("c", Set.of("admin")), new Prompt("d", Set.of()), new Prompt("e", Set.of())));
        McpRequestHandler handler = new McpRequestHandler(registry, new McpConfig(null, 4096, 1000L, "t", "1", null, 1), auth, header -> new Caller("u", Set.of("mcp:tools"), Json.createObjectBuilder().build()));
        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonObject page = result(send(handler, "prompts/list", cursor == null ? null : Json.createObjectBuilder().add("cursor", cursor)));
            assertEquals("private", page.getString("cacheScope"));
            assertEquals(1, page.getJsonArray("prompts").size());
            seen.addAll(names(page.getJsonArray("prompts"), "name"));
            cursor = page.containsKey("nextCursor") ? page.getString("nextCursor") : null;
            pages++;
        } while (cursor != null);
        assertEquals(List.of("b", "d", "e"), seen);
        assertEquals(3, pages);
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private static McpRequestHandler tools(int pageSize, String... names) {
        List<McpToolHandler> tools = new ArrayList<>();
        for (String name : names) {
            tools.add(new Tool(name));
        }
        return new McpRequestHandler(McpRegistry.of(tools), new McpConfig(null, 65536, 1000L, "t", "1", null, pageSize));
    }

    private static List<String> walk(McpRequestHandler handler, String method, String key, String field) {
        List<String> seen = new ArrayList<>();
        String cursor = null;
        for (int guard = 0; guard < 20; guard++) {
            JsonObject page = result(send(handler, method, cursor == null ? null : Json.createObjectBuilder().add("cursor", cursor)));
            seen.addAll(names(page.getJsonArray(key), field));
            if (!page.containsKey("nextCursor")) {
                return seen;
            }
            cursor = page.getString("nextCursor");
        }
        throw new AssertionError("pagination did not terminate");
    }

    private static List<String> names(JsonArray array, String field) {
        return array.getValuesAs(JsonObject.class).stream().map(o -> o.getString(field)).toList();
    }

    private static McpHttpResponse list(McpRequestHandler handler, String method, Object cursorOrParams) {
        if (cursorOrParams instanceof String cursor) {
            return send(handler, method, Json.createObjectBuilder().add("cursor", cursor));
        }
        return send(handler, method, (JsonObjectBuilder) cursorOrParams);
    }

    private static McpHttpResponse send(McpRequestHandler handler, String method, JsonObjectBuilder params) {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.put("MCP-Protocol-Version", VERSION);
        headers.put("Mcp-Method", method);
        JsonObjectBuilder all = params == null ? Json.createObjectBuilder() : params;
        JsonObject meta = Json.createObjectBuilder().add("io.modelcontextprotocol/protocolVersion", VERSION).add("io.modelcontextprotocol/clientCapabilities", Json.createObjectBuilder()).build();
        String body = Json.createObjectBuilder().add("jsonrpc", "2.0").add("id", 1).add("method", method).add("params", all.add("_meta", meta)).build().toString();
        return handler.handle(headers::get, new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static JsonObject json(McpHttpResponse response) {
        return Json.createReader(new StringReader(response.body())).readObject();
    }

    private static JsonObject result(McpHttpResponse response) {
        assertEquals(200, response.status(), response.body());
        return json(response).getJsonObject("result");
    }


    private record Tool(String name) implements McpToolHandler {

        @Override
        public String description() {
            return name;
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


    private record Res(String uri) implements McpResourceHandler {

        @Override
        public String name() {
            return uri;
        }

        @Override
        public ResourceResult read(ResourceCall call) {
            return ResourceResult.text(call.uri(), "text/plain", "x");
        }
    }


    private record Prompt(String name, Set<String> requiredScopes) implements McpPromptHandler {

        @Override
        public PromptResult get(PromptCall call) {
            return PromptResult.of(PromptMessage.user("x"));
        }
    }
}
