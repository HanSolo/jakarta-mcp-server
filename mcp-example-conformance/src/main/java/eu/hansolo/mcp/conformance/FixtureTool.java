package eu.hansolo.mcp.conformance;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import eu.hansolo.mcp.api.McpToolHandler;
import eu.hansolo.mcp.api.ToolCall;
import eu.hansolo.mcp.api.ToolResult;

/** A tool defined by a name, a schema and a function, so the fixtures stay short. */
final class FixtureTool implements McpToolHandler {

    /** The tool body; may throw. */
    @FunctionalInterface
    interface Body {
        ToolResult call(ToolCall call) throws Exception;
    }

    static final JsonObject NO_ARGUMENTS = Json.createObjectBuilder().add("type", "object").add("additionalProperties", false).build();

    private final String     name;
    private final String     description;
    private final JsonObject schema;
    private final Body       body;


    FixtureTool(String name, String description, Body body) {
        this(name, description, NO_ARGUMENTS, body);
    }

    FixtureTool(String name, String description, JsonObject schema, Body body) {
        this.name        = name;
        this.description = description;
        this.schema      = schema;
        this.body        = body;
    }


    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public JsonObject inputSchema() {
        return schema;
    }

    @Override
    public ToolResult call(ToolCall call) throws Exception {
        return body.call(call);
    }
}
