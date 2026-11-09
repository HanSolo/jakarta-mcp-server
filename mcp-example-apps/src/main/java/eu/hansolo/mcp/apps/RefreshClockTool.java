package eu.hansolo.mcp.apps;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import eu.hansolo.mcp.api.Apps;
import eu.hansolo.mcp.api.McpToolHandler;
import eu.hansolo.mcp.api.ToolCall;
import eu.hansolo.mcp.api.ToolResult;

/**
 * The page's own tool: visible to the page ({@code "app"}) but not offered to the model. A client must refuse an app calling a tool
 * without {@code "app"} visibility and must not list a tool without {@code "model"} to the model; the server only declares it.
 */
@ApplicationScoped
public class RefreshClockTool implements McpToolHandler {

    @Override
    public String name() {
        return "refresh_clock";
    }

    @Override
    public String description() {
        return "Returns the current server time for the clock page";
    }

    @Override
    public JsonObject inputSchema() {
        return Json.createObjectBuilder().add("type", "object").add("additionalProperties", false).build();
    }

    @Override
    public JsonObject meta() {
        return Apps.toolMeta(ClockPage.URI, "app");
    }

    @Override
    public ToolResult call(ToolCall call) {
        return Clock.result("Server time: ");
    }
}
