package eu.hansolo.mcp.itfixture;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import eu.hansolo.mcp.api.McpToolHandler;
import eu.hansolo.mcp.api.ToolCall;
import eu.hansolo.mcp.api.ToolResult;

/** Refuses the call unless the client declared {@link FixtureExtension#ID}. */
@ApplicationScoped
public class ExtensionRequiredTool implements McpToolHandler {

    @Override
    public String name() {
        return "ext_required";
    }

    @Override
    public String description() {
        return "Needs the it-fixture extension";
    }

    @Override
    public JsonObject inputSchema() {
        return Json.createObjectBuilder().add("type", "object").add("additionalProperties", false).build();
    }

    @Override
    public ToolResult call(ToolCall call) {
        call.requireClientExtension(FixtureExtension.ID);
        return ToolResult.text("extension present");
    }
}
