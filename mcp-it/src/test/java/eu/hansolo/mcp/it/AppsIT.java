package eu.hansolo.mcp.it;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The MCP Apps example on a real server: the extension is announced, the tool names its page, the page is served, and the text fallback adapts. */
class AppsIT {

    private static final String UI   = "io.modelcontextprotocol/ui";
    private static final String PAGE = "ui://clock/app.html";
    private static final String MIME = "text/html;profile=mcp-app";

    private static GenericContainer<?> container;
    private static McpClient           client;


    @BeforeAll
    static void start() {
        Assumptions.assumeTrue(ServerFixture.dockerAvailable(), "Docker is not available: skipping integration tests");
        container = ServerFixture.create(ServerFixture.appsWar(), Map.of(), false);
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
    void discoverAnnouncesTheUiExtension() throws Exception {
        JsonObject extensions = client.discover(null).json().getJsonObject("result").getJsonObject("capabilities").getJsonObject("extensions");
        assertTrue(extensions.containsKey(UI));
    }

    @Test
    void theToolNamesItsPage() throws Exception {
        JsonObject show = tool("show_clock");
        assertEquals(PAGE, show.getJsonObject("_meta").getJsonObject("ui").getString("resourceUri"));
        JsonObject refresh = tool("refresh_clock");
        assertEquals(PAGE, refresh.getJsonObject("_meta").getJsonObject("ui").getString("resourceUri"));
        assertEquals("app", refresh.getJsonObject("_meta").getJsonObject("ui").getJsonArray("visibility").getString(0));
        assertEquals(1, refresh.getJsonObject("_meta").getJsonObject("ui").getJsonArray("visibility").size());
    }

    @Test
    void theToolsReturnStructuredTime() throws Exception {
        for (String name : new String[] { "show_clock", "refresh_clock" }) {
            McpClient.Reply reply = client.rpc("tools/call", 1, Json.createObjectBuilder().add("name", name), name, null, Json.createObjectBuilder().build());
            assertEquals(200, reply.status(), reply.body());
            String now = reply.json().getJsonObject("result").getJsonObject("structuredContent").getString("now");
            assertTrue(now.startsWith("20") && now.endsWith("Z"), now);
        }
    }

    @Test
    void thePageIsServedAsAnApp() throws Exception {
        McpClient.Reply reply = client.rpc("resources/read", 1, Json.createObjectBuilder().add("uri", PAGE), PAGE, null, Json.createObjectBuilder().build());
        assertEquals(200, reply.status(), reply.body());
        JsonObject contents = reply.json().getJsonObject("result").getJsonArray("contents").getJsonObject(0);
        assertEquals(MIME, contents.getString("mimeType"));
        assertTrue(contents.getString("text").contains("<!doctype html>"));
        assertEquals(false, contents.getJsonObject("_meta").getJsonObject("ui").getBoolean("prefersBorder"));
        String page = contents.getString("text");
        assertTrue(page.contains("ui/initialize") && page.contains("ui/notifications/tool-result"), "the page does not speak the host protocol");
        assertTrue(!page.contains("http://") && !page.contains("https://"), "the page must not load anything from outside");
    }

    @Test
    void theTextAdaptsToTheClient() throws Exception {
        JsonObject withApps = Json.createObjectBuilder().add("extensions", Json.createObjectBuilder().add(UI, Json.createObjectBuilder().add("mimeTypes", Json.createArrayBuilder().add(MIME)))).build();
        assertTrue(text(client.rpc("tools/call", 1, Json.createObjectBuilder().add("name", "show_clock"), "show_clock", null, withApps)).startsWith("Showing the clock"));
        assertTrue(text(client.rpc("tools/call", 1, Json.createObjectBuilder().add("name", "show_clock"), "show_clock", null, Json.createObjectBuilder().build())).startsWith("Server time"));
    }

    private static JsonObject tool(String name) throws Exception {
        for (jakarta.json.JsonValue v : client.toolsList(null).json().getJsonObject("result").getJsonArray("tools")) {
            if (name.equals(v.asJsonObject().getString("name"))) {
                return v.asJsonObject();
            }
        }
        throw new AssertionError("no tool " + name);
    }

    private static String text(McpClient.Reply reply) {
        assertEquals(200, reply.status(), reply.body());
        return reply.json().getJsonObject("result").getJsonArray("content").getJsonObject(0).getString("text");
    }
}
