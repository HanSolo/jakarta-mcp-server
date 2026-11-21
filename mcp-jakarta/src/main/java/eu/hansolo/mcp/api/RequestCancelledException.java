package eu.hansolo.mcp.api;

/**
 * Thrown by {@link ProgressReporter#report} when the client has closed the stream. Over Streamable HTTP, closing the stream
 * cancels the request, so the tool should stop working and not catch this exception; the server sends nothing further.
 */
public class RequestCancelledException extends RuntimeException {

    /**
     * The request was cancelled.
     *
     * @param message what was cancelled
     * @param cause   the interruption or failure that ended it, or null
     */
    public RequestCancelledException(String message, Throwable cause) {
        super(message, cause);
    }
}
