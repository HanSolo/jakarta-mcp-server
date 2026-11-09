package eu.hansolo.mcp.server;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import eu.hansolo.mcp.api.Content;
import eu.hansolo.mcp.api.Icon;
import eu.hansolo.mcp.api.McpPromptHandler;
import eu.hansolo.mcp.api.McpResourceHandler;
import eu.hansolo.mcp.api.McpResourceTemplateHandler;
import eu.hansolo.mcp.api.McpToolHandler;
import eu.hansolo.mcp.api.Meta;
import eu.hansolo.mcp.api.PromptCall;
import eu.hansolo.mcp.api.PromptResult;
import eu.hansolo.mcp.api.ResourceCall;
import eu.hansolo.mcp.api.ResourceContent;
import eu.hansolo.mcp.api.ResourceResult;
import eu.hansolo.mcp.api.ToolCall;
import eu.hansolo.mcp.api.ToolResult;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Icons, {@code _meta} and rich content blocks, from the handler to the JSON the client gets. */
class McpDescriptorsTest {

    private static final String VERSION = "2026-07-28";
    private static final JsonObject META = Json.createObjectBuilder().add("com.example/team", "billing").add("ui", Json.createObjectBuilder().add("resourceUri", "ui://chart")).build();

    private final McpConfig         config  = new McpConfig(null, 65536, 1000L, "t", "1", null);
    private final McpRegistry       registry = McpRegistry.of(List.of(new Plain(), new Decorated()), List.of(new DecoratedResource()), List.of(new DecoratedTemplate()), List.of(new DecoratedPrompt()));
    private final McpRequestHandler handler  = new McpRequestHandler(registry, config);


    // ---- listings --------------------------------------------------------------------------------------------------

    @Test
    void aPlainToolHasNeitherIconsNorMeta() {
        JsonObject tool = tool("plain");
        assertFalse(tool.containsKey("icons"));
        assertFalse(tool.containsKey("_meta"));
    }

    @Test
    void aToolListsItsIconsAndMeta() {
        JsonObject tool = tool("decorated");
        JsonObject icon = tool.getJsonArray("icons").getJsonObject(0);
        assertEquals("https://example.com/tool.png", icon.getString("src"));
        assertEquals("image/png", icon.getString("mimeType"));
        assertEquals("48x48", icon.getJsonArray("sizes").getString(0));
        assertEquals("dark", icon.getString("theme"));
        assertEquals("billing", tool.getJsonObject("_meta").getString("com.example/team"));
        assertEquals("ui://chart", tool.getJsonObject("_meta").getJsonObject("ui").getString("resourceUri"));
    }

    @Test
    void resourcesTemplatesAndPromptsListThemToo() {
        JsonObject resource = result(send("resources/list", null, null)).getJsonArray("resources").getJsonObject(0);
        assertEquals("data:image/png;base64,AAAA", resource.getJsonArray("icons").getJsonObject(0).getString("src"));
        assertEquals("billing", resource.getJsonObject("_meta").getString("com.example/team"));
        JsonObject template = result(send("resources/templates/list", null, null)).getJsonArray("resourceTemplates").getJsonObject(0);
        assertEquals(1, template.getJsonArray("icons").size());
        assertTrue(template.containsKey("_meta"));
        JsonObject prompt = result(send("prompts/list", null, null)).getJsonArray("prompts").getJsonObject(0);
        assertEquals(1, prompt.getJsonArray("icons").size());
        assertTrue(prompt.containsKey("_meta"));
    }

    // ---- results ---------------------------------------------------------------------------------------------------

    @Test
    void aToolResultKeepsItsMetaAndGetsTheServerInfoAlongside() {
        JsonObject meta = result(send("tools/call", "decorated", Json.createObjectBuilder().add("name", "decorated"))).getJsonObject("_meta");
        assertEquals("trace-1", meta.getString("com.example/trace"));
        assertEquals("t", meta.getJsonObject("io.modelcontextprotocol/serverInfo").getString("name"));
    }

    @Test
    void aHandlerCannotOverwriteTheServerInfoKey() {
        JsonObject meta = result(send("tools/call", "decorated", Json.createObjectBuilder().add("name", "decorated").add("arguments", Json.createObjectBuilder().add("spoof", true)))).getJsonObject("_meta");
        assertEquals("t", meta.getJsonObject("io.modelcontextprotocol/serverInfo").getString("name"));
    }

    @Test
    void aResultWithoutMetaStillCarriesTheServerInfo() {
        JsonObject meta = result(send("tools/call", "plain", Json.createObjectBuilder().add("name", "plain"))).getJsonObject("_meta");
        assertEquals(1, meta.size());
    }

    @Test
    void contentBlocksOfAllKindsReachTheClient() {
        JsonArray content = result(send("tools/call", "decorated", Json.createObjectBuilder().add("name", "decorated"))).getJsonArray("content");
        assertEquals(List.of("text", "image", "audio", "resource_link", "resource"), content.stream().map(v -> ((JsonObject) v).getString("type")).toList());
        assertEquals("AQID", content.getJsonObject(1).getString("data"));
        assertEquals("report://q1", content.getJsonObject(3).getString("uri"));
        assertEquals("ui://x", content.getJsonObject(4).getJsonObject("resource").getString("uri"));
    }

    @Test
    void resourceContentCarriesItsMeta() {
        JsonObject contents = result(send("resources/read", null, Json.createObjectBuilder().add("uri", "ui://chart"))).getJsonArray("contents").getJsonObject(0);
        assertEquals("text/html;profile=mcp-app", contents.getString("mimeType"));
        assertEquals("self", contents.getJsonObject("_meta").getJsonObject("ui").getString("csp"));
    }

    @Test
    void aPromptCanEmbedAResourceWithItsMeta() {
        JsonObject message = result(send("prompts/get", "decorated", Json.createObjectBuilder().add("name", "decorated"))).getJsonArray("messages").getJsonObject(0);
        assertEquals("resource", message.getJsonObject("content").getString("type"));
        assertTrue(message.getJsonObject("content").getJsonObject("resource").containsKey("_meta"));
    }

    // ---- validation ------------------------------------------------------------------------------------------------

    @Test
    void aBadMetaKeyFailsAtStartup() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> McpRegistry.of(List.of(new BadMeta())));
        assertTrue(e.getMessage().contains("not a valid _meta key"), e.getMessage());
    }

    @Test
    void nullIconsFailAtStartup() {
        assertThrows(IllegalStateException.class, () -> McpRegistry.of(List.of(new NullIcons())));
    }

    @Test
    void metaKeysFollowThePrefixAndNameRules() {
        Meta.validate(Json.createObjectBuilder().add("a", 1).add("com.example/key_1", 1).add("io.modelcontextprotocol/ui", 1).add("a-b.c/x.y", 1).build(), "t");
        for (String bad : List.of("1com/key", "com.example//key", "com.example/-key", "com.example/key-", "bad key", "-x/y", "a..b/c", "a./b")) {
            assertThrows(IllegalArgumentException.class, () -> Meta.validate(Json.createObjectBuilder().add(bad, 1).build(), "t"));
        }
    }

    @Test
    void metaParsesFromAnnotationText() {
        assertEquals(null, Meta.parse("", "t"));
        assertEquals("b", Meta.parse("{\"com.example/a\":\"b\"}", "t").getString("com.example/a"));
        assertThrows(IllegalArgumentException.class, () -> Meta.parse("[1]", "t"));
        assertThrows(IllegalArgumentException.class, () -> Meta.parse("{nope", "t"));
        assertThrows(IllegalArgumentException.class, () -> Meta.parse("{\"bad key\":1}", "t"));
    }

    @Test
    void iconsRefuseUnsafeSourcesAndBadFields() {
        assertThrows(IllegalArgumentException.class, () -> Icon.of("javascript:alert(1)"));
        assertThrows(IllegalArgumentException.class, () -> Icon.of("file:///etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> Icon.of("/relative.png"));
        assertThrows(IllegalArgumentException.class, () -> new Icon("https://e.com/i.png", null, List.of("big"), null));
        assertThrows(IllegalArgumentException.class, () -> new Icon("https://e.com/i.png", null, null, "blue"));
        assertEquals("any", new Icon("https://e.com/i.svg", "image/svg+xml", List.of("any"), "light").toJson().getJsonArray("sizes").getString(0));
    }

    @Test
    void contentHelpersBuildTheSpecShapes() {
        JsonObject text = Content.text("hi", Content.Annotations.forAudience("user", "assistant").withPriority(0.5).withLastModified(Instant.parse("2026-01-02T03:04:05Z")));
        assertEquals("user", text.getJsonObject("annotations").getJsonArray("audience").getString(0));
        assertEquals(0.5, text.getJsonObject("annotations").getJsonNumber("priority").doubleValue());
        assertEquals("2026-01-02T03:04:05Z", text.getJsonObject("annotations").getString("lastModified"));
        JsonObject link = Content.resourceLink("report://q1", "q1").title("Q1").description("d").mimeType("application/pdf").size(12).icons(List.of(Icon.of("https://e.com/i.png"))).build();
        assertEquals("resource_link", link.getString("type"));
        assertEquals(12, link.getInt("size"));
        assertEquals(1, link.getJsonArray("icons").size());
        assertThrows(IllegalArgumentException.class, () -> Content.image(new byte[1], " "));
        assertThrows(IllegalArgumentException.class, () -> Content.Annotations.forAudience("robot"));
        assertThrows(IllegalArgumentException.class, () -> Content.Annotations.forAudience("user").withPriority(1.5));
        assertThrows(IllegalArgumentException.class, () -> Content.resourceLink("", "n"));
    }


    // ---- plumbing --------------------------------------------------------------------------------------------------

    private JsonObject tool(String name) {
        JsonArray tools = result(send("tools/list", null, null)).getJsonArray("tools");
        return tools.stream().map(JsonObject.class::cast).filter(t -> name.equals(t.getString("name"))).findFirst().orElseThrow();
    }

    private McpHttpResponse send(String method, String name, JsonObjectBuilder params) {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.put("MCP-Protocol-Version", VERSION);
        headers.put("Mcp-Method", method);
        // a JsonObjectBuilder is spent by build() with the real JSON-P implementation, so build the params once
        JsonObject built = params == null ? Json.createObjectBuilder().build() : params.build();
        if (name != null) {
            headers.put("Mcp-Name", name);
        } else if (built.containsKey("uri")) {
            headers.put("Mcp-Name", built.getString("uri"));
        }
        JsonObjectBuilder all = Json.createObjectBuilder(built);
        JsonObject meta = Json.createObjectBuilder().add("io.modelcontextprotocol/protocolVersion", VERSION).add("io.modelcontextprotocol/clientCapabilities", Json.createObjectBuilder()).build();
        String body = Json.createObjectBuilder().add("jsonrpc", "2.0").add("id", 1).add("method", method).add("params", all.add("_meta", meta)).build().toString();
        return handler.handle(headers::get, new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static JsonObject result(McpHttpResponse response) {
        assertEquals(200, response.status(), response.body());
        return Json.createReader(new StringReader(response.body())).readObject().getJsonObject("result");
    }

    private static JsonObject noArguments() {
        return Json.createObjectBuilder().add("type", "object").add("additionalProperties", false).build();
    }

    private static JsonObject anyArguments() {
        return Json.createObjectBuilder().add("type", "object").build();
    }


    private static class Plain implements McpToolHandler {

        @Override
        public String name() {
            return "plain";
        }

        @Override
        public String description() {
            return "plain";
        }

        @Override
        public JsonObject inputSchema() {
            return noArguments();
        }

        @Override
        public ToolResult call(ToolCall call) {
            return ToolResult.text("ok");
        }
    }

    private static class Decorated implements McpToolHandler {

        @Override
        public String name() {
            return "decorated";
        }

        @Override
        public String description() {
            return "decorated";
        }

        @Override
        public JsonObject inputSchema() {
            return anyArguments();
        }

        @Override
        public List<Icon> icons() {
            return List.of(new Icon("https://example.com/tool.png", "image/png", List.of("48x48"), "dark"));
        }

        @Override
        public JsonObject meta() {
            return META;
        }

        @Override
        public ToolResult call(ToolCall call) {
            JsonObjectBuilder meta = Json.createObjectBuilder().add("com.example/trace", "trace-1");
            if (call.arguments().containsKey("spoof")) {
                meta.add("io.modelcontextprotocol/serverInfo", "forged");
            }
            JsonArray content = Content.array(Content.text("t"), Content.image(new byte[] {1, 2, 3}, "image/png"), Content.audio(new byte[] {4}, "audio/wav"), Content.resourceLink("report://q1", "q1").build(), Content.resource(ResourceContent.text("ui://x", "text/html", "<p>")));
            return new ToolResult.Complete(content, null, false).withMeta(meta.build());
        }
    }

    private static class BadMeta extends Plain {

        @Override
        public JsonObject meta() {
            return Json.createObjectBuilder().add("bad key", 1).build();
        }
    }

    private static class NullIcons extends Plain {

        @Override
        public List<Icon> icons() {
            return null;
        }
    }

    private static class DecoratedResource implements McpResourceHandler {

        @Override
        public String uri() {
            return "ui://chart";
        }

        @Override
        public String name() {
            return "chart";
        }

        @Override
        public String mimeType() {
            return "text/html;profile=mcp-app";
        }

        @Override
        public List<Icon> icons() {
            return List.of(Icon.of("data:image/png;base64,AAAA"));
        }

        @Override
        public JsonObject meta() {
            return META;
        }

        @Override
        public ResourceResult read(ResourceCall call) {
            JsonObject meta = Json.createObjectBuilder().add("ui", Json.createObjectBuilder().add("csp", "self")).build();
            return ResourceResult.of(ResourceContent.text("ui://chart", "text/html;profile=mcp-app", "<html></html>").withMeta(meta));
        }
    }

    private static class DecoratedTemplate implements McpResourceTemplateHandler {

        @Override
        public String uriTemplate() {
            return "doc://{id}";
        }

        @Override
        public String name() {
            return "doc";
        }

        @Override
        public List<Icon> icons() {
            return List.of(Icon.of("https://example.com/doc.png"));
        }

        @Override
        public JsonObject meta() {
            return META;
        }

        @Override
        public ResourceResult read(ResourceCall call) {
            return ResourceResult.text(call.uri(), "text/plain", "x");
        }
    }

    private static class DecoratedPrompt implements McpPromptHandler {

        @Override
        public String name() {
            return "decorated";
        }

        @Override
        public List<Icon> icons() {
            return List.of(Icon.of("https://example.com/p.png"));
        }

        @Override
        public JsonObject meta() {
            return META;
        }

        @Override
        public PromptResult get(PromptCall call) {
            return new PromptResult.Complete(null, List.of(eu.hansolo.mcp.api.PromptMessage.user(Content.resource(ResourceContent.text("doc://1", "text/plain", "x").withMeta(META)))));
        }
    }
}
