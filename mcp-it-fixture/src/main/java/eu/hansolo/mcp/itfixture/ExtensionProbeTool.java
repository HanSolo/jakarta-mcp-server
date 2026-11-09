package eu.hansolo.mcp.itfixture;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import eu.hansolo.mcp.api.McpToolHandler;
import eu.hansolo.mcp.api.ToolCall;
import eu.hansolo.mcp.api.ToolResult;

/** Reports whether the client declared {@link FixtureExtension#ID} on this request, and with which settings. */
@ApplicationScoped
public class ExtensionProbeTool implements McpToolHandler {

    @Override
    public String name() {
        return "ext_probe";
    }

    @Override
    public String description() {
        return "Reports what the client declared about the it-fixture extension";
    }

    @Override
    public JsonObject inputSchema() {
        return Json.createObjectBuilder().add("type", "object").add("additionalProperties", false).build();
    }

    @Override
    public ToolResult call(ToolCall call) {
        var settings = call.clientExtension(FixtureExtension.ID);
        JsonObjectBuilder result = Json.createObjectBuilder().add("declared", settings.isPresent());
        settings.ifPresent(s -> result.add("settings", s));
        return ToolResult.structured(result.build());
    }
}
