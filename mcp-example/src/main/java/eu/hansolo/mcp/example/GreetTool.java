package eu.hansolo.mcp.example;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import eu.hansolo.mcp.api.McpToolHandler;
import eu.hansolo.mcp.api.ToolCall;
import eu.hansolo.mcp.api.ToolResult;
import eu.hansolo.mcp.protocol.McpProtocolException;

/**
 * Demonstrates Multi Round-Trip Requests. Without a name argument the tool asks the client to elicit one from the user
 * (resultType "input_required"); the client then retries the same call with the answer in inputResponses. The server keeps
 * nothing between the two requests.
 */
@ApplicationScoped
public class GreetTool implements McpToolHandler {

    private static final String INPUT_KEY = "name";

    @Override
    public String name() {
        return "greet";
    }

    @Override
    public String description() {
        return "Greets someone. If no name is given, asks the user for one (requires the client's elicitation capability).";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject properties = Json.createObjectBuilder().add("name", Json.createObjectBuilder().add("type", "string").add("description", "Who to greet; omit to be asked")).build();
        return Json.createObjectBuilder().add("type", "object").add("properties", properties).add("additionalProperties", false).build();
    }

    @Override
    public ToolResult call(ToolCall call) {
        var given = call.stringArgument("name");
        if (given.isPresent()) {
            return greeting(given.get());
        }
        var answer = call.inputResponse(INPUT_KEY);
        if (answer.isPresent()) {
            return answerToResult(answer.get());
        }
        if (!call.clientSupports("elicitation")) {
            throw McpProtocolException.missingClientCapability("elicitation");
        }
        return ToolResult.inputRequired(elicitationRequest(), null);
    }

    private static ToolResult answerToResult(JsonObject answer) {
        if (!(answer.get("action") instanceof JsonString action) || !action.getString().equals("accept")) {
            return ToolResult.error("No name was provided.");
        }
        JsonValue content = answer.get("content");
        if (content instanceof JsonObject object && object.get("name") instanceof JsonString name && !name.getString().isBlank()) {
            return greeting(name.getString());
        }
        return ToolResult.error("No name was provided.");
    }

    private static ToolResult greeting(String name) {
        return ToolResult.text("Hello, " + name + "!");
    }

    private static JsonObject elicitationRequest() {
        JsonObject schema = Json.createObjectBuilder().add("type", "object").add("properties", Json.createObjectBuilder().add("name", Json.createObjectBuilder().add("type", "string").add("title", "Name"))).add("required", Json.createArrayBuilder().add("name")).build();
        JsonObject params = Json.createObjectBuilder().add("mode", "form").add("message", "Who should I greet?").add("requestedSchema", schema).build();
        JsonObject request = Json.createObjectBuilder().add("method", "elicitation/create").add("params", params).build();
        return Json.createObjectBuilder().add(INPUT_KEY, request).build();
    }
}
