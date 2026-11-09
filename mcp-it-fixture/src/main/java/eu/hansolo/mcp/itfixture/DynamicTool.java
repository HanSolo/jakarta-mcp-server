package eu.hansolo.mcp.itfixture;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import eu.hansolo.mcp.api.McpToolHandler;
import eu.hansolo.mcp.api.ToolCall;
import eu.hansolo.mcp.api.ToolResult;

/** A tool that is registered at runtime and answers with its own name. */
final class DynamicTool implements McpToolHandler {

    private final String name;


    DynamicTool(String name) {
        this.name = name;
    }


    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return "Added at runtime by the integration test";
    }

    @Override
    public JsonObject inputSchema() {
        return Json.createObjectBuilder().add("type", "object").add("additionalProperties", false).build();
    }

    @Override
    public ToolResult call(ToolCall call) {
        return ToolResult.text("hello from " + name);
    }
}
