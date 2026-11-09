package eu.hansolo.mcp.itfixture;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import eu.hansolo.mcp.api.Elicitation;
import eu.hansolo.mcp.api.InputRequests;
import eu.hansolo.mcp.api.McpToolHandler;
import eu.hansolo.mcp.api.RequestCancelledException;
import eu.hansolo.mcp.api.ToolCall;
import eu.hansolo.mcp.api.ToolResult;
import eu.hansolo.mcp.protocol.McpProtocolException;

/**
 * Runs as a task when the client supports the Tasks extension. {@code mode}: {@code quick} finishes after {@code millis}; {@code ask}
 * waits for the client's answer; {@code wait} runs until it is cancelled; {@code fail} ends with a JSON-RPC error.
 */
@ApplicationScoped
public class TaskDemoTool implements McpToolHandler {

    @Override
    public String name() {
        return "task_demo";
    }

    @Override
    public String description() {
        return "Long-running work as a task (for the integration tests)";
    }

    @Override
    public JsonObject inputSchema() {
        return Json.createObjectBuilder().add("type", "object")
                   .add("properties", Json.createObjectBuilder()
                                          .add("mode", Json.createObjectBuilder().add("type", "string").add("enum", Json.createArrayBuilder().add("quick").add("ask").add("wait").add("fail")))
                                          .add("millis", Json.createObjectBuilder().add("type", "integer").add("minimum", 0).add("maximum", 60000)))
                   .add("required", Json.createArrayBuilder().add("mode")).add("additionalProperties", false).build();
    }

    @Override
    public ToolResult call(ToolCall call) {
        String mode   = call.stringArgument("mode").orElse("quick");
        int    millis = call.arguments().containsKey("millis") ? call.arguments().getInt("millis") : 100;
        return ToolResult.task(ctx -> {
            ctx.status("started " + mode);
            switch (mode) {
                case "ask" -> {
                    JsonObject answers = ctx.ask(InputRequests.of("confirm", Elicitation.form("Proceed?").string("why", p -> p.required())));
                    return ToolResult.text("confirmed: " + answers.getJsonObject("confirm").getJsonObject("content").getString("why"));
                }
                case "wait" -> {
                    while (!ctx.isCancelled()) {
                        Thread.sleep(20);
                    }
                    throw new RequestCancelledException("stopped", null);
                }
                case "fail" -> throw new McpProtocolException(200, -32602, "the demo failed on purpose", null);
                default -> {
                    Thread.sleep(millis);
                    return ToolResult.text("finished " + mode);
                }
            }
        });
    }
}
