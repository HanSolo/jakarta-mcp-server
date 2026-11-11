package eu.hansolo.mcp.itfixture;

import eu.hansolo.mcp.api.McpToolHandler;
import eu.hansolo.mcp.api.ToolCall;
import eu.hansolo.mcp.api.ToolResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.Json;
import jakarta.json.JsonObject;

/**
 * "Starts a job somewhere else": the job is nothing but a time stamp in the handle, so the integration tests can follow it without a second
 * system. {@code millis} is how long the job runs (default 1000). The task follows it through {@link ExternalDemoJob}, without a worker thread.
 */
@ApplicationScoped
public class ExternalDemoTool implements McpToolHandler {

    @Override
    public String name() {
        return "external_demo";
    }

    @Override
    public String description() {
        return "A job in another system that the task follows without a worker (for the integration tests)";
    }

    @Override
    public JsonObject inputSchema() {
        return Json.createObjectBuilder().add("type", "object")
                   .add("properties", Json.createObjectBuilder().add("millis", Json.createObjectBuilder().add("type", "integer").add("minimum", 0).add("maximum", 3_600_000)))
                   .add("additionalProperties", false).build();
    }

    @Override
    public ToolResult call(ToolCall call) {
        long millis = call.arguments().containsKey("millis") ? call.arguments().getJsonNumber("millis").longValue() : 1000L;
        return ToolResult.externalTask(taskId -> Json.createObjectBuilder().add("readyAt", System.currentTimeMillis() + millis).build());
    }
}
