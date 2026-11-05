package eu.hansolo.mcp.example;

import jakarta.enterprise.context.ApplicationScoped;
import eu.hansolo.mcp.api.McpParam;
import eu.hansolo.mcp.api.McpPrompt;

import java.util.Optional;

/** A prompt by annotation: parameters are the prompt arguments, and a returned String becomes one user message. */
@ApplicationScoped
public class ExamplePrompts {

    @McpPrompt(description = "Asks the model to summarize a text.")
    public String summarize(@McpParam(description = "The text to summarize") String text, @McpParam(description = "Desired style, for example 'bullet points' or 'one sentence'") Optional<String> style) {
        return "Please summarize the following text" + style.map(s -> " as " + s).orElse("") + ":\n\n" + text;
    }
}
