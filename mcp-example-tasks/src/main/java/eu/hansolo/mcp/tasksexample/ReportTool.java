package eu.hansolo.mcp.tasksexample;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import eu.hansolo.mcp.api.Elicitation;
import eu.hansolo.mcp.api.InputRequests;
import eu.hansolo.mcp.api.McpToolHandler;
import eu.hansolo.mcp.api.RequestCancelledException;
import eu.hansolo.mcp.api.TaskSupport;
import eu.hansolo.mcp.api.ToolCall;
import eu.hansolo.mcp.api.ToolResult;

import java.util.concurrent.TimeUnit;

/**
 * A report that takes a while. A client that declared the Tasks extension gets a task id at once and follows the work with
 * {@code tasks/get}; the work reports which section it is on, asks the client whether to publish, and stops when the task is
 * cancelled. A client without the extension waits for the same work on the request and gets the plain result (the question is
 * skipped there, because only a task can wait for an answer).
 */
@ApplicationScoped
public class ReportTool implements McpToolHandler {

    private static final int MAX_SECTIONS = 10;

    @Override
    public String name() {
        return "build_report";
    }

    @Override
    public String description() {
        return "Builds a report section by section (about a second each). Runs as a task for clients that support tasks.";
    }

    @Override
    public JsonObject inputSchema() {
        return Json.createObjectBuilder().add("type", "object")
                   .add("properties", Json.createObjectBuilder()
                                          .add("topic", Json.createObjectBuilder().add("type", "string").add("description", "What the report is about"))
                                          .add("sections", Json.createObjectBuilder().add("type", "integer").add("minimum", 1).add("maximum", MAX_SECTIONS).add("description", "Number of sections (default 4)")))
                   .add("required", Json.createArrayBuilder().add("topic")).add("additionalProperties", false).build();
    }

    @Override
    public TaskSupport taskSupport() {
        return TaskSupport.OPTIONAL;
    }

    @Override
    public ToolResult call(ToolCall call) {
        String  topic    = call.stringArgument("topic").orElse("untitled");
        int     sections = call.arguments().containsKey("sections") ? call.arguments().getInt("sections") : 4;
        boolean canAsk   = call.clientSupportsTasks();
        return ToolResult.task(ctx -> {
            for (int i = 1; i <= sections; i++) {
                if (ctx.isCancelled()) {
                    throw new RequestCancelledException("stopped after " + (i - 1) + " sections", null);
                }
                ctx.status("section " + i + " of " + sections);   // visible in tasks/get while the task is working
                TimeUnit.SECONDS.sleep(1);
            }
            if (!canAsk) {
                return ToolResult.text("Report on '" + topic + "' with " + sections + " sections (not published: this client cannot be asked)");
            }
            // the task is input_required until the client answers with tasks/update
            JsonObject answers = ctx.ask(InputRequests.of("publish", Elicitation.form("Publish the report on '" + topic + "'?").bool("publish", p -> p.required())));
            JsonObject answer  = answers.getJsonObject("publish");
            boolean    publish = "accept".equals(answer.getString("action", "")) && answer.getJsonObject("content").getBoolean("publish", false);
            return ToolResult.text("Report on '" + topic + "' with " + sections + " sections, " + (publish ? "published" : "kept as a draft"));
        });
    }
}
