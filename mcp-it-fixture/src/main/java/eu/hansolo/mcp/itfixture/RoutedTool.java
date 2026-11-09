package eu.hansolo.mcp.itfixture;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import eu.hansolo.mcp.api.McpToolHandler;
import eu.hansolo.mcp.api.ToolCall;
import eu.hansolo.mcp.api.ToolResult;

/** A tool whose schema asks for {@code Mcp-Param-*} headers: a string, an integer, a boolean and a nested string. */
@ApplicationScoped
public class RoutedTool implements McpToolHandler {

    private static final JsonObject SCHEMA = Json.createReader(new java.io.StringReader("""
            {
              "type": "object",
              "properties": {
                "region": {"type": "string", "x-mcp-header": "Region"},
                "count": {"type": "integer", "x-mcp-header": "Count"},
                "urgent": {"type": "boolean", "x-mcp-header": "Urgent"},
                "target": {"type": "object", "properties": {"zone": {"type": "string", "x-mcp-header": "Zone"}}},
                "note": {"type": "string"}
              },
              "additionalProperties": false
            }
            """)).readObject();


    @Override
    public String name() {
        return "routed_echo";
    }

    @Override
    public String description() {
        return "Echoes its arguments; the schema asks for Mcp-Param-* headers (for the integration tests)";
    }

    @Override
    public JsonObject inputSchema() {
        return SCHEMA;
    }

    @Override
    public ToolResult call(ToolCall call) {
        return ToolResult.structured(call.arguments());
    }
}
