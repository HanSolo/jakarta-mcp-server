package eu.hansolo.mcp.server;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import eu.hansolo.mcp.api.Extensions;
import eu.hansolo.mcp.api.McpExtension;
import eu.hansolo.mcp.api.McpToolHandler;
import eu.hansolo.mcp.api.ToolCall;
import eu.hansolo.mcp.api.ToolResult;
import eu.hansolo.mcp.auth.AuthConfig;
import eu.hansolo.mcp.api.Caller;
import eu.hansolo.mcp.protocol.ErrorCodes;
import eu.hansolo.mcp.protocol.McpProtocolException;
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

/** Announcing extensions in server/discover and reading what the client declared. */
class McpExtensionsTest {

    private static final String VERSION = "2026-07-28";
    private static final String UI      = "io.modelcontextprotocol/ui";

    private final McpConfig   config   = new McpConfig(null, 65536, 1000L, "t", "1", null);
    private final McpRegistry registry = McpRegistry.of(List.of(new Probe(false), new Probe(true)));


    @Test
    void noExtensionsMeansNoExtensionsField() {
        McpRequestHandler handler = new McpRequestHandler(registry, config);
        assertFalse(result(send(handler, "server/discover", null, null, null)).getJsonObject("capabilities").containsKey("extensions"));
    }

    @Test
    void announcedExtensionsAreListedWithTheirSettings() {
        McpExtensions extensions = new McpExtensions(List.of(new Ext(UI, null), new Ext("com.example/reports", Json.createObjectBuilder().add("max", 3).build())));
        JsonObject listed = result(send(withExtensions(extensions), "server/discover", null, null, null)).getJsonObject("capabilities").getJsonObject("extensions");
        assertEquals(0, listed.getJsonObject(UI).size());
        assertEquals(3, listed.getJsonObject("com.example/reports").getInt("max"));
        assertTrue(extensions.announces(UI));
    }

    @Test
    void invalidDuplicateAndNullSettingsFailAtStartup() {
        assertThrows(IllegalStateException.class, () -> new McpExtensions(List.of(new Ext("no-slash", null))));
        assertThrows(IllegalStateException.class, () -> new McpExtensions(List.of(new Ext("com.example/", null))));
        assertThrows(IllegalStateException.class, () -> new McpExtensions(List.of(new Ext(UI, null), new Ext(UI, null))));
        assertThrows(IllegalStateException.class, () -> new McpExtensions(List.of(new NullSettings())));
    }

    @Test
    void theBaseAppsClassIsNotABeanButASubclassStaysOne() {
        // the library archive uses bean discovery mode "all": without @Vetoed the container registers the base class too and a subclass announces the extension twice
        assertTrue(eu.hansolo.mcp.api.McpAppsExtension.class.isAnnotationPresent(jakarta.enterprise.inject.Vetoed.class));
        class Sub extends eu.hansolo.mcp.api.McpAppsExtension { }
        assertFalse(Sub.class.isAnnotationPresent(jakarta.enterprise.inject.Vetoed.class));
    }

    @Test
    void identifiersFollowTheVendorPrefixRule() {
        assertTrue(Extensions.isValidId("io.modelcontextprotocol/oauth-client-credentials"));
        assertTrue(Extensions.isValidId("com.example/my_ext.v2"));
        assertFalse(Extensions.isValidId("example"));
        assertFalse(Extensions.isValidId("/name"));
        assertFalse(Extensions.isValidId("com.example/-x"));
        assertFalse(Extensions.isValidId("1com/x"));
        assertFalse(Extensions.isValidId(null));
    }

    @Test
    void aToolSeesWhatTheClientDeclared() {
        JsonObject caps = Json.createObjectBuilder().add("extensions", Json.createObjectBuilder().add(UI, Json.createObjectBuilder().add("mimeTypes", Json.createArrayBuilder().add("text/html;profile=mcp-app")))).build();
        JsonObject structured = result(send(new McpRequestHandler(registry, config), "tools/call", "probe", Json.createObjectBuilder().add("name", "probe"), caps)).getJsonObject("structuredContent");
        assertTrue(structured.getBoolean("declared"));
        assertEquals("text/html;profile=mcp-app", structured.getJsonObject("settings").getJsonArray("mimeTypes").getString(0));
    }

    @Test
    void aToolFallsBackWhenTheClientDeclaredNothingOrSomethingElse() {
        McpRequestHandler handler = new McpRequestHandler(registry, config);
        assertFalse(result(send(handler, "tools/call", "probe", Json.createObjectBuilder().add("name", "probe"), null)).getJsonObject("structuredContent").getBoolean("declared"));
        JsonObject other = Json.createObjectBuilder().add("extensions", Json.createObjectBuilder().add("com.other/x", Json.createObjectBuilder())).build();
        assertFalse(result(send(handler, "tools/call", "probe", Json.createObjectBuilder().add("name", "probe"), other)).getJsonObject("structuredContent").getBoolean("declared"));
        JsonObject notAnObject = Json.createObjectBuilder().add("extensions", Json.createObjectBuilder().add(UI, true)).build();
        assertFalse(result(send(handler, "tools/call", "probe", Json.createObjectBuilder().add("name", "probe"), notAnObject)).getJsonObject("structuredContent").getBoolean("declared"));
    }

    @Test
    void aMandatoryExtensionIsRefusedWith32021AndNamesWhatIsMissing() {
        McpHttpResponse response = send(new McpRequestHandler(registry, config), "tools/call", "probe_required", Json.createObjectBuilder().add("name", "probe_required"), null);
        assertEquals(400, response.status());
        JsonObject error = json(response).getJsonObject("error");
        assertEquals(ErrorCodes.MISSING_REQUIRED_CLIENT_CAPABILITY, error.getInt("code"));
        assertTrue(error.getJsonObject("data").getJsonObject("requiredCapabilities").getJsonObject("extensions").containsKey(UI));
    }

    @Test
    void aMandatoryExtensionPassesWhenDeclared() {
        JsonObject caps = Json.createObjectBuilder().add("extensions", Json.createObjectBuilder().add(UI, Json.createObjectBuilder())).build();
        McpHttpResponse response = send(new McpRequestHandler(registry, config), "tools/call", "probe_required", Json.createObjectBuilder().add("name", "probe_required"), caps);
        assertEquals(200, response.status(), response.body());
    }


    private McpRequestHandler withExtensions(McpExtensions extensions) {
        return new McpRequestHandler(registry, config, AuthConfig.disabled(), header -> Caller.ANONYMOUS, new McpNotifications(config), extensions);
    }

    private static McpHttpResponse send(McpRequestHandler target, String method, String name, JsonObjectBuilder params, JsonObject clientCapabilities) {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.put("MCP-Protocol-Version", VERSION);
        headers.put("Mcp-Method", method);
        if (name != null) {
            headers.put("Mcp-Name", name);
        }
        JsonObjectBuilder all = params == null ? Json.createObjectBuilder() : params;
        JsonObject meta = Json.createObjectBuilder().add("io.modelcontextprotocol/protocolVersion", VERSION).add("io.modelcontextprotocol/clientCapabilities", clientCapabilities == null ? Json.createObjectBuilder().build() : clientCapabilities).build();
        String body = Json.createObjectBuilder().add("jsonrpc", "2.0").add("id", 1).add("method", method).add("params", all.add("_meta", meta)).build().toString();
        return target.handle(headers::get, new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static JsonObject json(McpHttpResponse response) {
        return Json.createReader(new StringReader(response.body())).readObject();
    }

    private static JsonObject result(McpHttpResponse response) {
        assertEquals(200, response.status(), response.body());
        return json(response).getJsonObject("result");
    }


    private record Ext(String id, JsonObject settings) implements McpExtension {

        @Override
        public JsonObject settings() {
            return settings == null ? McpExtension.super.settings() : settings;
        }
    }

    private static class NullSettings implements McpExtension {

        @Override
        public String id() {
            return "com.example/null";
        }

        @Override
        public JsonObject settings() {
            return null;
        }
    }

    private static class Probe implements McpToolHandler {

        private final boolean required;


        Probe(boolean required) {
            this.required = required;
        }


        @Override
        public String name() {
            return required ? "probe_required" : "probe";
        }

        @Override
        public String description() {
            return "reports what the client declared";
        }

        @Override
        public JsonObject inputSchema() {
            return Json.createObjectBuilder().add("type", "object").build();
        }

        @Override
        public ToolResult call(ToolCall call) {
            if (required) {
                call.requireClientExtension(UI);
                return ToolResult.text("ok");
            }
            var settings = call.clientExtension(UI);
            JsonObjectBuilder out = Json.createObjectBuilder().add("declared", settings.isPresent());
            settings.ifPresent(s -> out.add("settings", s));
            return ToolResult.structured(out.build());
        }
    }
}
