package eu.hansolo.mcp.itfixture;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import eu.hansolo.mcp.api.McpToolHandler;
import eu.hansolo.mcp.api.ToolCall;
import eu.hansolo.mcp.api.ToolResult;

/** The same, for a tool written as an {@code McpToolHandler}: the binding sits on {@code call} only, so the registry's calls to {@code name()} and the others are not audited. */
@ApplicationScoped
public class AuditedHandlerTool implements McpToolHandler {

    @Inject CallLog log;


    @Override
    public String name() {
        return "audited_handler";
    }

    @Override
    public String description() {
        return "Echoes the message; every call goes through an interceptor";
    }

    @Override
    public JsonObject inputSchema() {
        return Json.createObjectBuilder().add("type", "object")
                   .add("properties", Json.createObjectBuilder().add("message", Json.createObjectBuilder().add("type", "string")))
                   .add("required", Json.createArrayBuilder().add("message")).add("additionalProperties", false).build();
    }

    @Override
    @Audited
    public ToolResult call(ToolCall call) {
        String message = call.arguments().getString("message");
        log.record("ran audited_handler " + message);
        return ToolResult.text("handler echo: " + message);
    }
}
