package eu.hansolo.mcp.itfixture;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import eu.hansolo.mcp.api.McpToolHandler;
import eu.hansolo.mcp.api.ToolCall;
import eu.hansolo.mcp.api.ToolResult;

/** Works for the given time without reporting progress, so the only traffic on a streamed call is the keep-alive. */
@ApplicationScoped
public class QuietTool implements McpToolHandler {

    @Override
    public String name() {
        return "quiet";
    }

    @Override
    public String description() {
        return "Works silently for a number of milliseconds";
    }

    @Override
    public JsonObject inputSchema() {
        return Json.createObjectBuilder().add("type", "object")
                   .add("properties", Json.createObjectBuilder().add("millis", Json.createObjectBuilder().add("type", "integer").add("minimum", 0).add("maximum", 60000)))
                   .add("required", Json.createArrayBuilder().add("millis")).add("additionalProperties", false).build();
    }

    @Override
    public ToolResult call(ToolCall call) {
        long end = System.currentTimeMillis() + call.arguments().getInt("millis");
        while (System.currentTimeMillis() < end && !call.isCancelled()) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return ToolResult.text(call.isCancelled() ? "cancelled" : "done");
    }
}
