package eu.hansolo.mcp.itfixture;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import eu.hansolo.mcp.api.McpToolHandler;
import eu.hansolo.mcp.api.ToolCall;
import eu.hansolo.mcp.api.ToolResult;

/**
 * A tool whose schema nests objects in a {@code oneOf} and constrains strings, numbers and arrays, to see the argument check work
 * over HTTP on a real server. It computes the area, so a passing call proves the arguments arrived intact.
 */
@ApplicationScoped
public class ShapeTool implements McpToolHandler {

    private static final JsonObject SCHEMA = Json.createReader(new java.io.StringReader("""
            {
              "type": "object",
              "properties": {
                "shape": {
                  "oneOf": [
                    {
                      "type": "object",
                      "properties": {"kind": {"const": "circle"}, "radius": {"type": "number", "exclusiveMinimum": 0}},
                      "required": ["kind", "radius"],
                      "additionalProperties": false
                    },
                    {
                      "type": "object",
                      "properties": {"kind": {"const": "rect"}, "width": {"type": "integer", "minimum": 1}, "height": {"type": "integer", "minimum": 1}},
                      "required": ["kind", "width", "height"],
                      "additionalProperties": false
                    }
                  ]
                },
                "tags": {
                  "type": "array",
                  "items": {"type": "string", "pattern": "^[a-z]+$"},
                  "maxItems": 3,
                  "uniqueItems": true
                }
              },
              "required": ["shape"],
              "additionalProperties": false
            }
            """)).readObject();


    @Override
    public String name() {
        return "shape_area";
    }

    @Override
    public String description() {
        return "Area of a circle or a rectangle (nested oneOf schema, for the integration tests)";
    }

    @Override
    public JsonObject inputSchema() {
        return SCHEMA;
    }

    @Override
    public ToolResult call(ToolCall call) {
        JsonObject shape = call.arguments().getJsonObject("shape");
        double     area  = "circle".equals(shape.getString("kind")) ? Math.PI * shape.getJsonNumber("radius").doubleValue() * shape.getJsonNumber("radius").doubleValue() : shape.getInt("width") * (double) shape.getInt("height");
        return ToolResult.structured(Json.createObjectBuilder().add("kind", shape.getString("kind")).add("area", area).build());
    }
}
