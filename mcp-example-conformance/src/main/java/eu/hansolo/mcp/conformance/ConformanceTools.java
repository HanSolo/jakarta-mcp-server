package eu.hansolo.mcp.conformance;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import eu.hansolo.mcp.api.McpToolHandler;
import eu.hansolo.mcp.api.ToolCall;
import eu.hansolo.mcp.api.ToolResult;
import eu.hansolo.mcp.protocol.McpProtocolException;
import eu.hansolo.mcp.server.McpNotifications;
import eu.hansolo.mcp.server.McpRequestStates;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The tools the MCP conformance suite looks for, by name. They exist only to give the suite something to call: each one produces
 * exactly the output a scenario checks. The Multi Round-Trip tools keep no state; where a scenario needs a state they sign it with
 * {@link McpRequestStates}.
 */
public final class ConformanceTools {

    private final McpNotifications notifications;
    private final McpRequestStates states;


    public ConformanceTools(McpNotifications notifications, McpRequestStates states) {
        this.notifications = notifications;
        this.states        = states;
    }


    /** All tools by name, in registration order. */
    public Map<String, McpToolHandler> all() {
        List<FixtureTool> tools = new ArrayList<>();
        tools.add(new FixtureTool("test_simple_text", "Returns a simple text block.", c -> ToolResult.text("This is a simple text response for testing.")));
        tools.add(new FixtureTool("test_image_content", "Returns an image block.", c -> Content.blocks(Content.image())));
        tools.add(new FixtureTool("test_audio_content", "Returns an audio block.", c -> Content.blocks(Content.audio())));
        tools.add(new FixtureTool("test_embedded_resource", "Returns an embedded resource block.", c -> Content.blocks(Content.resource("test://embedded-resource", "text/plain", "This is an embedded resource content."))));
        tools.add(new FixtureTool("test_multiple_content_types", "Returns text, an image and an embedded resource.", c -> Content.blocks(Content.text("Multiple content types test:"), Content.image(), Content.resource("test://mixed-content-resource", "application/json", "{\"test\":\"data\",\"value\":123}"))));
        tools.add(new FixtureTool("test_error_handling", "Returns a tool error.", c -> ToolResult.error("This tool intentionally returns an error for testing")));
        tools.add(new FixtureTool("test_tool_with_progress", "Reports progress while it runs.", this::withProgress));
        tools.add(new FixtureTool("test_missing_capability", "Needs the sampling capability from the client.", this::missingCapability));
        tools.add(new FixtureTool("test_streaming_elicitation", "Completes without asking anything.", c -> ToolResult.text("Streaming complete")));
        tools.add(new FixtureTool("test_logging_tool", "Never logs to the client.", c -> ToolResult.text("Logging evaluated")));
        tools.add(new FixtureTool("test_trigger_tool_change", "Announces that the tool list changed.", c -> {
            notifications.toolsListChanged();
            return ToolResult.text("Mutation triggered");
        }));
        tools.add(new FixtureTool("test_trigger_prompt_change", "Announces that the prompt list changed.", c -> {
            notifications.promptsListChanged();
            return ToolResult.text("Mutation triggered");
        }));
        tools.add(new FixtureTool("json_schema_2020_12_tool", "Declares a JSON Schema 2020-12 input schema.", jsonSchema(), c -> ToolResult.text("Valid input for " + c.stringArgument("name").orElse("nobody"))));

        tools.add(new FixtureTool("test_input_required_result_elicitation", "Asks the client for the user's name.", this::elicitation));
        tools.add(new FixtureTool("test_input_required_result_sampling", "Asks the client to sample a model.", this::sampling));
        tools.add(new FixtureTool("test_input_required_result_list_roots", "Asks the client for its roots.", this::listRoots));
        tools.add(new FixtureTool("test_input_required_result_request_state", "Asks for a confirmation and carries a signed state.", this::requestState));
        tools.add(new FixtureTool("test_input_required_result_multiple_inputs", "Asks for several inputs at once.", this::multipleInputs));
        tools.add(new FixtureTool("test_input_required_result_multi_round", "Asks in two rounds.", this::multiRound));
        tools.add(new FixtureTool("test_input_required_result_tampered_state", "Refuses a request state that was altered.", this::tamperedState));
        tools.add(new FixtureTool("test_input_required_result_capabilities", "Asks only for what the client declared it supports.", this::capabilities));

        Map<String, McpToolHandler> byName = new LinkedHashMap<>();
        tools.forEach(t -> byName.put(t.name(), t));
        return byName;
    }


    private ToolResult withProgress(ToolCall call) throws InterruptedException {
        for (int step : new int[]{0, 50, 100}) {
            call.progress(step, 100d, "Completed step " + step + " of 100");
            Thread.sleep(50);
        }
        return ToolResult.text("Progress complete");
    }

    private ToolResult missingCapability(ToolCall call) {
        if (!call.clientSupports("sampling")) {
            throw McpProtocolException.missingClientCapability("sampling");
        }
        return ToolResult.text("Success");
    }

    private ToolResult elicitation(ToolCall call) {
        var name = Requests.acceptedString(call.inputResponses(), "user_name", "name");
        if (name.isPresent()) {
            return ToolResult.text("Hello, " + name.get() + "!");
        }
        return ToolResult.inputRequired(Requests.requests("user_name", Requests.elicit("What is your name?", "name", "string")), null);
    }

    private ToolResult sampling(ToolCall call) {
        var text = Requests.sampledText(call.inputResponses(), "sample_request");
        if (text.isPresent()) {
            return ToolResult.text("Sampling result: " + text.get());
        }
        return ToolResult.inputRequired(Requests.requests("sample_request", Requests.sample("What is the capital of France?", 100)), null);
    }

    private ToolResult listRoots(ToolCall call) {
        var count = Requests.rootCount(call.inputResponses(), "roots_request");
        if (count.isPresent()) {
            return ToolResult.text("Found " + count.get() + " root(s)");
        }
        return ToolResult.inputRequired(Requests.requests("roots_request", Requests.roots()), null);
    }

    private ToolResult requestState(ToolCall call) {
        if (call.requestState() != null) {
            states.require(call.requestState(), context(call));
            if (Requests.accepted(call.inputResponses(), "confirm").map(c -> c.get("ok")).filter(v -> v == jakarta.json.JsonValue.TRUE).isPresent()) {
                return ToolResult.text("state-ok: requestState validated");
            }
        }
        return ToolResult.inputRequired(Requests.requests("confirm", Requests.elicit("Please confirm", "ok", "boolean")), states.sign("confirm", context(call)));
    }

    private ToolResult multipleInputs(ToolCall call) {
        JsonObject answers = call.inputResponses();
        if (call.requestState() != null) {
            states.require(call.requestState(), context(call));
        }
        var name     = Requests.acceptedString(answers, "user_name", "name");
        var greeting = Requests.sampledText(answers, "greeting");
        var roots    = Requests.rootCount(answers, "client_roots");
        if (call.requestState() != null && name.isPresent() && greeting.isPresent() && roots.isPresent()) {
            return ToolResult.text("Name: " + name.get() + "; Greeting: " + greeting.get() + "; Roots: " + roots.get());
        }
        JsonObject requests = Json.createObjectBuilder().add("user_name", Requests.elicit("What is your name?", "name", "string")).add("greeting", Requests.sample("Generate a greeting", 50)).add("client_roots", Requests.roots()).build();
        return ToolResult.inputRequired(requests, states.sign("multiple", context(call)));
    }

    private ToolResult multiRound(ToolCall call) {
        String state = call.requestState() == null ? null : states.require(call.requestState(), context(call));
        if ("round1".equals(state)) {
            var name = Requests.acceptedString(call.inputResponses(), "step1", "name");
            if (name.isPresent()) {
                return ToolResult.inputRequired(Requests.requests("step2", Requests.elicit("Step 2: What is your favorite color?", "color", "string")), states.sign("round2:" + name.get(), context(call)));
            }
        } else if (state != null && state.startsWith("round2:")) {
            var color = Requests.acceptedString(call.inputResponses(), "step2", "color");
            if (color.isPresent()) {
                return ToolResult.text("Multi-round complete for " + state.substring("round2:".length()) + " who likes " + color.get());
            }
            return ToolResult.inputRequired(Requests.requests("step2", Requests.elicit("Step 2: What is your favorite color?", "color", "string")), states.sign(state, context(call)));
        }
        return ToolResult.inputRequired(Requests.requests("step1", Requests.elicit("Step 1: What is your name?", "name", "string")), states.sign("round1", context(call)));
    }

    private ToolResult tamperedState(ToolCall call) {
        if (call.requestState() != null) {
            states.require(call.requestState(), context(call));
            if (Requests.accepted(call.inputResponses(), "confirm").isPresent()) {
                return ToolResult.text("integrity-ok: state verified");
            }
        }
        return ToolResult.inputRequired(Requests.requests("confirm", Requests.elicit("Please confirm", "ok", "boolean")), states.sign("integrity", context(call)));
    }

    private ToolResult capabilities(ToolCall call) {
        var builder = Json.createObjectBuilder();
        if (call.clientSupports("elicitation")) {
            builder.add("elicit_input", Requests.elicit("Elicit request", "value", "string"));
        }
        if (call.clientSupports("sampling")) {
            builder.add("sample_input", Requests.sample("Sample request", 50));
        }
        JsonObject requests = builder.build();
        if (requests.isEmpty()) {
            return ToolResult.text("No supported capabilities declared");
        }
        JsonObject answers = call.inputResponses();
        if (answers != null && requests.keySet().stream().allMatch(answers::containsKey)) {
            return ToolResult.text("capabilities-ok: received " + String.join(",", requests.keySet()));
        }
        return ToolResult.inputRequired(requests, null);
    }

    /** A state is only good for the tool and the caller it was issued to. */
    private static String context(ToolCall call) {
        return call.name() + "|" + call.caller().subject();
    }

    private static JsonObject jsonSchema() {
        return Json.createReader(new java.io.StringReader("""
                {
                  "$schema": "https://json-schema.org/draft/2020-12/schema",
                  "type": "object",
                  "$defs": {"address": {"$anchor": "addressDef", "type": "object", "properties": {"street": {"type": "string"}, "city": {"type": "string"}}, "additionalProperties": false}},
                  "properties": {
                    "name": {"type": "string"},
                    "address": {"$ref": "#/$defs/address"},
                    "contactMethod": {"type": "string", "enum": ["phone", "email"]},
                    "phone": {"type": "string"},
                    "email": {"type": "string"}
                  },
                  "allOf": [{"anyOf": [{"required": ["phone"]}, {"required": ["email"]}]}],
                  "if": {"properties": {"contactMethod": {"const": "phone"}}, "required": ["contactMethod"]},
                  "then": {"required": ["phone"]},
                  "else": {"required": ["email"]},
                  "additionalProperties": false
                }
                """)).readObject();
    }
}
