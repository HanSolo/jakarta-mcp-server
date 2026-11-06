package eu.hansolo.mcp.api;

/**
 * Thrown by {@link ProgressReporter#report} when the client has closed the stream. Over Streamable HTTP, closing the stream
 * cancels the request, so the tool should stop working and not catch this exception; the server sends nothing further.
 */
public class RequestCancelledException extends RuntimeException {

    public RequestCancelledException(String message, Throwable cause) {
        super(message, cause);
    }
}
