package eu.hansolo.mcp.apps;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import eu.hansolo.mcp.api.Apps;
import eu.hansolo.mcp.api.McpToolHandler;
import eu.hansolo.mcp.api.ToolCall;
import eu.hansolo.mcp.api.ToolResult;

/**
 * A tool with a page. Clients that support MCP Apps show {@code ui://clock/app.html} when they render the result and hand the
 * page the {@code structuredContent}; every other client shows the text. The text is not optional: the specification requires a
 * usable {@code content} even when a page is available.
 */
@ApplicationScoped
public class ClockTool implements McpToolHandler {

    @Override
    public String name() {
        return "show_clock";
    }

    @Override
    public String description() {
        return "Shows the current server time, as a clock where the client can display pages";
    }

    @Override
    public JsonObject inputSchema() {
        return Json.createObjectBuilder().add("type", "object").add("additionalProperties", false).build();
    }

    @Override
    public JsonObject meta() {
        return Apps.toolMeta(ClockPage.URI);
    }

    @Override
    public ToolResult call(ToolCall call) {
        return Clock.result(call.clientSupportsApps() ? "Showing the clock. Server time: " : "Server time: ");
    }
}
