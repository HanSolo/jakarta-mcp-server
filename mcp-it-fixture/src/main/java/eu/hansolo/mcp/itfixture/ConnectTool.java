package eu.hansolo.mcp.itfixture;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import eu.hansolo.mcp.api.Elicitation;
import eu.hansolo.mcp.api.InputRequests;
import eu.hansolo.mcp.api.McpToolHandler;
import eu.hansolo.mcp.api.ToolCall;
import eu.hansolo.mcp.api.ToolResult;
import eu.hansolo.mcp.server.McpRequestStates;

import java.util.Optional;

/** Sends the user to a page (URL mode) and carries a signed request state through the retry. */
@ApplicationScoped
public class ConnectTool implements McpToolHandler {

    @Inject
    McpRequestStates states;


    @Override
    public String name() {
        return "connect_account";
    }

    @Override
    public String description() {
        return "Asks the user to connect an account on a page of this server";
    }

    @Override
    public JsonObject inputSchema() {
        return Json.createObjectBuilder().add("type", "object").add("additionalProperties", false).build();
    }

    @Override
    public ToolResult call(ToolCall call) {
        if (!call.clientSupportsElicitation(Elicitation.Mode.URL)) {
            return ToolResult.error("This client cannot open a page for the user");
        }
        String context = call.name() + "|" + call.caller().subject();
        if (call.requestState() != null) {
            String payload = states.require(call.requestState(), context);
            Optional<Elicitation.Answer> answer = Elicitation.answer(call.inputResponses(), "connect");
            if (answer.isPresent() && answer.get().accepted()) {
                return ToolResult.text("connected after " + payload);
            }
            return ToolResult.text("not connected");
        }
        return ToolResult.ask(InputRequests.of("connect", Elicitation.url("Connect your account on our page", "https://accounts.example.com/connect?ticket=abc")), states.sign("connect", context));
    }
}
