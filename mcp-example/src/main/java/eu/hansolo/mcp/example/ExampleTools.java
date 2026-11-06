package eu.hansolo.mcp.example;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import eu.hansolo.mcp.api.McpParam;
import eu.hansolo.mcp.api.McpTool;
import eu.hansolo.mcp.api.RequestCancelledException;
import eu.hansolo.mcp.api.ToolCall;
import eu.hansolo.mcp.api.ToolExecutionException;
import eu.hansolo.mcp.server.McpNotifications;

import java.util.Collections;
import java.util.Optional;
import java.util.logging.Logger;

/** The annotation style: a plain CDI bean whose public methods become tools. Compare with {@link GreetTool}, which implements the handler interface directly. */
@ApplicationScoped
public class ExampleTools {

    private static final Logger LOG = Logger.getLogger(ExampleTools.class.getName());

    @Inject
    McpNotifications notifications;

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

    /**
     * Shows streamed responses: a client that sends a {@code progressToken} and accepts {@code text/event-stream} receives one
     * progress notification per step, while the call is still running, then the result. Any other client just waits and gets the result.
     */
    @McpTool(description = "Counts down, reporting progress at every step. Progress notifications reach clients that send a progressToken and accept text/event-stream.", readOnlyHint = McpTool.Hint.TRUE)
    public String countdown(@McpParam(description = "Number of steps, 1 to 10") int steps, @McpParam(description = "Pause between steps in milliseconds, 0 to 2000 (default 500)") Optional<Integer> delayMs, ToolCall call) {
        int delay = delayMs.orElse(500);
        if (steps < 1 || steps > 10) {
            throw new ToolExecutionException("steps must be between 1 and 10, got " + steps);
        }
        if (delay < 0 || delay > 2000) {
            throw new ToolExecutionException("delayMs must be between 0 and 2000, got " + delay);
        }
        for (int step = 1; step <= steps; step++) {
            try {
                Thread.sleep(delay);
                call.progress(step, (double) steps, "Step " + step + " of " + steps);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RequestCancelledException("Interrupted", e);
            } catch (RequestCancelledException e) {
                // the client closed the stream: stop working, the server sends nothing more
                LOG.info("countdown cancelled at step " + step + " of " + steps);
                throw e;
            }
        }
        LOG.info("countdown finished after " + steps + " steps");
        return "Counted down " + steps + " steps";
    }

    /**
     * Shows list-changed notifications. A real server calls {@code notifications.toolsListChanged()} when the tools its clients can
     * see really changed; this tool only fires the notification so the mechanism can be tried: open a
     * {@code subscriptions/listen} stream with {@code toolsListChanged}, call this tool, and the stream receives the notification.
     */
    @McpTool(description = "Tells every client that listens for tool-list changes to read the list again (a demonstration of list-changed notifications).", readOnlyHint = McpTool.Hint.TRUE, idempotentHint = McpTool.Hint.TRUE)
    public String refresh_tools() {
        notifications.toolsListChanged();
        return "Notified " + notifications.activeSubscriptions() + " open subscription(s) on this server instance";
    }
}
