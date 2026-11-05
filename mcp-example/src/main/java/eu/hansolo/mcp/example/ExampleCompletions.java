package eu.hansolo.mcp.example;

import jakarta.enterprise.context.ApplicationScoped;
import eu.hansolo.mcp.api.CompletionResult;
import eu.hansolo.mcp.api.McpComplete;

import java.util.List;

/** Completion sources by annotation: suggestions for the {@code style} argument of the summarize prompt and the {@code name} variable of the greeting template. */
@ApplicationScoped
public class ExampleCompletions {

    private static final List<String> STYLES = List.of("bullet points", "one sentence", "formal", "for a five-year-old");
    private static final List<String> NAMES  = List.of("Ada", "Alan", "Grace", "Linus");


    @McpComplete(prompt = "summarize", argument = "style")
    public CompletionResult styles(String typed) {
        return CompletionResult.matching(STYLES, typed);
    }

    @McpComplete(resourceTemplate = "greeting://{name}", argument = "name")
    public CompletionResult names(String typed) {
        return CompletionResult.matching(NAMES, typed);
    }
}
