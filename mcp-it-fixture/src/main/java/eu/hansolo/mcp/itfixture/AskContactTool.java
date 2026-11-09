package eu.hansolo.mcp.itfixture;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import eu.hansolo.mcp.api.Elicitation;
import eu.hansolo.mcp.api.InputRequests;
import eu.hansolo.mcp.api.McpToolHandler;
import eu.hansolo.mcp.api.ToolCall;
import eu.hansolo.mcp.api.ToolResult;

import java.util.List;
import java.util.Optional;

/** Asks the user for contact details (form mode) and asks again while the answer does not fit the form. */
@ApplicationScoped
public class AskContactTool implements McpToolHandler {

    @Override
    public String name() {
        return "ask_contact";
    }

    @Override
    public String description() {
        return "Asks the user for a name, an email address and an age";
    }

    @Override
    public JsonObject inputSchema() {
        return Json.createObjectBuilder().add("type", "object").add("additionalProperties", false).build();
    }

    @Override
    public ToolResult call(ToolCall call) {
        if (!call.clientSupportsElicitation(Elicitation.Mode.FORM)) {
            return ToolResult.error("This client cannot ask the user");
        }
        Optional<Elicitation.Answer> answer = Elicitation.answer(call.inputResponses(), "contact");
        if (answer.isEmpty()) {
            return ToolResult.ask(InputRequests.of("contact", form("Where can we reach you?")), null);
        }
        if (!answer.get().accepted()) {
            return ToolResult.text(answer.get().declined() ? "declined" : "cancelled");
        }
        List<String> problems = form("").problems(answer.get());
        if (!problems.isEmpty()) {
            return ToolResult.ask(InputRequests.of("contact", form("Please correct: " + String.join("; ", problems))), null);
        }
        return ToolResult.text("Hello " + answer.get().string("name").orElseThrow() + " <" + answer.get().string("email").orElseThrow() + ">, age " + answer.get().integer("age").map(String::valueOf).orElse("unknown"));
    }

    private static Elicitation.Form form(String message) {
        return Elicitation.form(message.isEmpty() ? "-" : message)
                .string("name", p -> p.minLength(2).required())
                .string("email", p -> p.format("email").required())
                .integer("age", p -> p.minimum(18));
    }
}
