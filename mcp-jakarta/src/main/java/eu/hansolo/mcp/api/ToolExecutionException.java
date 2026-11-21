package eu.hansolo.mcp.api;

/**
 * Thrown by an {@link McpTool} method (or a handler) to report that the tool ran but could not do what was asked. The message
 * is sent to the model as an {@code isError} result so it can recover, which means it must be written for that audience and must
 * not contain secrets or stack traces. Other exceptions are logged and replaced by a generic message.
 */
public class ToolExecutionException extends RuntimeException {

    private static final long serialVersionUID = 1L;


    /** A failure whose message the model sees, so that it can correct the call. */
    public ToolExecutionException(String message) {
        super(message);
    }

    /** A failure whose message the model sees; the cause is for the log and is not sent. */
    public ToolExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
