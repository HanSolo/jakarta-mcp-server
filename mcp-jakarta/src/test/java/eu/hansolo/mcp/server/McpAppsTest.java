package eu.hansolo.mcp.server;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import eu.hansolo.mcp.api.Apps;
import eu.hansolo.mcp.api.McpResourceHandler;
import eu.hansolo.mcp.api.McpToolHandler;
import eu.hansolo.mcp.api.ResourceCall;
import eu.hansolo.mcp.api.ResourceResult;
import eu.hansolo.mcp.api.ToolCall;
import eu.hansolo.mcp.api.ToolResult;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The MCP Apps helpers, their startup checks, and the whole thing through the request handler. */
class McpAppsTest {

    private static final String VERSION = "2026-07-28";
    private static final String PAGE    = "ui://chart";

    private final McpConfig         config  = new McpConfig(null, 65536, 1000L, "t", "1", null);
    private final McpRegistry       registry = McpRegistry.of(List.of(new ChartTool(Apps.toolMeta(PAGE, "model", "app"))), List.of(new ChartPage()), List.of(), List.of());
    private final McpRequestHandler handler  = new McpRequestHandler(registry, config);


    // ---- client side -----------------------------------------------------------------------------------------------

    @Test
    void aClientSupportsAppsOnlyWithTheExtensionAndTheMimeType() {
        assertTrue(Apps.clientSupports(caps(Json.createObjectBuilder().add("mimeTypes", Json.createArrayBuilder().add(Apps.MIME_TYPE)).build())));
        assertFalse(Apps.clientSupports(caps(Json.createObjectBuilder().build())));
        assertFalse(Apps.clientSupports(caps(Json.createObjectBuilder().add("mimeTypes", Json.createArrayBuilder().add("text/html")).build())));
        assertFalse(Apps.clientSupports(Json.createObjectBuilder().build()));
        assertFalse(Apps.clientSupports(null));
    }

    @Test
    void theToolSeesWhetherTheClientCanShowPages() {
        JsonObject yes = caps(Json.createObjectBuilder().add("mimeTypes", Json.createArrayBuilder().add(Apps.MIME_TYPE)).build());
        assertEquals("page", text(send("tools/call", "chart", Json.createObjectBuilder().add("name", "chart"), yes)));
        assertEquals("text only", text(send("tools/call", "chart", Json.createObjectBuilder().add("name", "chart"), Json.createObjectBuilder().build())));
    }

    // ---- the wiring on the wire ------------------------------------------------------------------------------------

    @Test
    void theToolListsItsBindingAndThePageIsServedAsAnApp() {
        JsonObject tool = result(send("tools/list", null, null, null)).getJsonArray("tools").getJsonObject(0);
        assertEquals(PAGE, tool.getJsonObject("_meta").getJsonObject("ui").getString("resourceUri"));
        assertEquals("app", tool.getJsonObject("_meta").getJsonObject("ui").getJsonArray("visibility").getString(1));
        JsonObject contents = result(send("resources/read", PAGE, Json.createObjectBuilder().add("uri", PAGE), null)).getJsonArray("contents").getJsonObject(0);
        assertEquals(Apps.MIME_TYPE, contents.getString("mimeType"));
        assertTrue(contents.getString("text").startsWith("<!doctype html>"));
        assertEquals("https://api.example.com", contents.getJsonObject("_meta").getJsonObject("ui").getJsonObject("csp").getJsonArray("connectDomains").getString(0));
        assertTrue(contents.getJsonObject("_meta").getJsonObject("ui").getBoolean("prefersBorder"));
    }

    // ---- startup checks --------------------------------------------------------------------------------------------

    @Test
    void aToolPointingAtAMissingPageFailsAtStartup() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> McpRegistry.of(List.of(new ChartTool(Apps.toolMeta("ui://nowhere")))));
        assertTrue(e.getMessage().contains("no such resource"), e.getMessage());
    }

    @Test
    void aToolAddedLaterIsCheckedToo() {
        assertThrows(IllegalStateException.class, () -> registry.addTool(new ChartTool2(Apps.toolMeta("ui://nowhere")), false));
        registry.addTool(new ChartTool2(Apps.toolMeta(PAGE, "app")), false);
        assertTrue(registry.find("chart2").isPresent());
    }

    @Test
    void aMalformedBindingFailsAtStartup() {
        JsonObject notUi = Json.createObjectBuilder().add("ui", Json.createObjectBuilder().add("resourceUri", "https://example.com/page")).build();
        assertThrows(IllegalStateException.class, () -> McpRegistry.of(List.of(new ChartTool(notUi))));
        JsonObject badVisibility = Json.createObjectBuilder().add("ui", Json.createObjectBuilder().add("resourceUri", PAGE).add("visibility", Json.createArrayBuilder().add("everyone"))).build();
        assertThrows(IllegalStateException.class, () -> McpRegistry.of(List.of(new ChartTool(badVisibility)), List.of(new ChartPage()), List.of(), List.of()));
        JsonObject emptyVisibility = Json.createObjectBuilder().add("ui", Json.createObjectBuilder().add("resourceUri", PAGE).add("visibility", Json.createArrayBuilder())).build();
        assertThrows(IllegalStateException.class, () -> McpRegistry.of(List.of(new ChartTool(emptyVisibility)), List.of(new ChartPage()), List.of(), List.of()));
    }

    @Test
    void aToolWithoutABindingNeedsNoPage() {
        McpRegistry.of(List.of(new ChartTool(null)));
    }

    // ---- builders --------------------------------------------------------------------------------------------------

    @Test
    void helpersRefuseWhatWouldBecomeAContentSecurityPolicyInjection() {
        assertThrows(IllegalArgumentException.class, () -> Apps.resourceMeta().connectDomains("https://a.com; script-src *"));
        assertThrows(IllegalArgumentException.class, () -> Apps.resourceMeta().resourceDomains("https://a.com 'unsafe-inline'"));
        assertThrows(IllegalArgumentException.class, () -> Apps.resourceMeta().frameDomains("*"));
        assertThrows(IllegalArgumentException.class, () -> Apps.resourceMeta().connectDomains("javascript:"));
        assertThrows(IllegalArgumentException.class, () -> Apps.resourceMeta().connectDomains("https://a.com/path"));
        assertThrows(IllegalArgumentException.class, () -> Apps.resourceMeta().connectDomains((String) null));
        assertThrows(IllegalArgumentException.class, () -> Apps.resourceMeta().domain("a b"));
        assertThrows(IllegalArgumentException.class, () -> Apps.resourceMeta().permission("usb"));
        assertThrows(IllegalArgumentException.class, () -> Apps.toolMeta("http://x"));
        assertThrows(IllegalArgumentException.class, () -> Apps.toolMeta(PAGE, "nobody"));
        assertThrows(IllegalArgumentException.class, () -> Apps.html("file:///x", "<p>", null));
    }

    @Test
    void theResourceMetaBuilderWritesTheSpecFields() {
        JsonObject ui = Apps.resourceMeta().connectDomains("https://api.example.com", "wss://live.example.com:8443").resourceDomains("https://*.cdn.example.com", "data:").frameDomains("https://embed.example.com").baseUriDomains("https://example.com").permission("camera").permission("clipboardWrite").domain("abc.sandbox.example").prefersBorder(false).build().getJsonObject("ui");
        assertEquals(2, ui.getJsonObject("csp").getJsonArray("connectDomains").size());
        assertEquals("data:", ui.getJsonObject("csp").getJsonArray("resourceDomains").getString(1));
        assertEquals("https://example.com", ui.getJsonObject("csp").getJsonArray("baseUriDomains").getString(0));
        assertTrue(ui.getJsonObject("permissions").containsKey("camera"));
        assertEquals(0, ui.getJsonObject("permissions").getJsonObject("clipboardWrite").size());
        assertEquals("abc.sandbox.example", ui.getString("domain"));
        assertFalse(ui.getBoolean("prefersBorder"));
        assertTrue(Apps.resourceMeta().build().getJsonObject("ui").isEmpty());
    }

    // ---- announcing ------------------------------------------------------------------------------------------------

    @Test
    void theSettingAnnouncesTheExtension() {
        assertFalse(McpExtensions.none().announces(Apps.EXTENSION_ID));
        System.setProperty("mcp.apps.enabled", "true");
        try {
            McpExtensions extensions = new McpExtensions(List.of());
            assertTrue(extensions.announces(Apps.EXTENSION_ID));
            assertEquals(0, extensions.toJson().getJsonObject(Apps.EXTENSION_ID).size());
            assertTrue(new McpExtensions(List.of(new eu.hansolo.mcp.api.McpAppsExtension())).announces(Apps.EXTENSION_ID));
        } finally {
            System.clearProperty("mcp.apps.enabled");
        }
    }


    // ---- plumbing --------------------------------------------------------------------------------------------------

    private static JsonObject caps(JsonObject uiSettings) {
        return Json.createObjectBuilder().add("extensions", Json.createObjectBuilder().add(Apps.EXTENSION_ID, uiSettings)).build();
    }

    private static String text(McpHttpResponse response) {
        return result(response).getJsonArray("content").getJsonObject(0).getString("text");
    }

    private McpHttpResponse send(String method, String name, JsonObjectBuilder params, JsonObject clientCapabilities) {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.put("MCP-Protocol-Version", VERSION);
        headers.put("Mcp-Method", method);
        if (name != null) {
            headers.put("Mcp-Name", name);
        }
        JsonObjectBuilder all = params == null ? Json.createObjectBuilder() : params;
        JsonObject meta = Json.createObjectBuilder().add("io.modelcontextprotocol/protocolVersion", VERSION).add("io.modelcontextprotocol/clientCapabilities", clientCapabilities == null ? Json.createObjectBuilder().build() : clientCapabilities).build();
        String body = Json.createObjectBuilder().add("jsonrpc", "2.0").add("id", 1).add("method", method).add("params", all.add("_meta", meta)).build().toString();
        return handler.handle(headers::get, new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static JsonObject result(McpHttpResponse response) {
        assertEquals(200, response.status(), response.body());
        return Json.createReader(new StringReader(response.body())).readObject().getJsonObject("result");
    }


    private static class ChartTool implements McpToolHandler {

        private final JsonObject meta;


        ChartTool(JsonObject meta) {
            this.meta = meta;
        }


        @Override
        public String name() {
            return "chart";
        }

        @Override
        public String description() {
            return "A chart";
        }

        @Override
        public JsonObject inputSchema() {
            return Json.createObjectBuilder().add("type", "object").build();
        }

        @Override
        public JsonObject meta() {
            return meta;
        }

        @Override
        public ToolResult call(ToolCall call) {
            return ToolResult.text(call.clientSupportsApps() ? "page" : "text only");
        }
    }

    private static class ChartTool2 extends ChartTool {

        ChartTool2(JsonObject meta) {
            super(meta);
        }


        @Override
        public String name() {
            return "chart2";
        }
    }

    private static class ChartPage implements McpResourceHandler {

        @Override
        public String uri() {
            return PAGE;
        }

        @Override
        public String name() {
            return "chart-page";
        }

        @Override
        public String mimeType() {
            return Apps.MIME_TYPE;
        }

        @Override
        public ResourceResult read(ResourceCall call) {
            return ResourceResult.of(Apps.html(PAGE, "<!doctype html><title>chart</title>", Apps.resourceMeta().connectDomains("https://api.example.com").prefersBorder(true).build()));
        }
    }
}
