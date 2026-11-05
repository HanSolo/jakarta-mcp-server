package eu.hansolo.mcp.example;

import jakarta.enterprise.context.ApplicationScoped;
import eu.hansolo.mcp.api.McpParam;
import eu.hansolo.mcp.api.McpTool;
import eu.hansolo.mcp.api.ToolExecutionException;

import java.util.Collections;
import java.util.Optional;

/** The annotation style: a plain CDI bean whose public methods become tools. Compare with {@link GreetTool}, which implements the handler interface directly. */
@ApplicationScoped
public class ExampleTools {

    /** A record result becomes structuredContent and an outputSchema. */
    public record Sum(@McpParam(description = "a + b") double sum) {
    }


    @McpTool(description = "Returns the given message unchanged.", readOnlyHint = McpTool.Hint.TRUE)
    public String echo(@McpParam(description = "Text to echo back") String message) {
        return message;
    }

    @McpTool(description = "Adds two numbers.", readOnlyHint = McpTool.Hint.TRUE, idempotentHint = McpTool.Hint.TRUE)
    public Sum add(@McpParam(description = "First addend") double a, @McpParam(description = "Second addend") double b) {
        return new Sum(a + b);
    }

    @McpTool(description = "Repeats a text 1 to 20 times, optionally joined by a separator.", readOnlyHint = McpTool.Hint.TRUE)
    public String repeat(@McpParam(description = "The text to repeat") String text, @McpParam(description = "How many times, 1 to 20") int times, @McpParam(description = "Inserted between repetitions") Optional<String> separator) {
        if (times < 1 || times > 20) {
            throw new ToolExecutionException("times must be between 1 and 20, got " + times);
        }
        return String.join(separator.orElse(""), Collections.nCopies(times, text));
    }
}
