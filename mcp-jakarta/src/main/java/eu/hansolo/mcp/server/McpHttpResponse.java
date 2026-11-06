package eu.hansolo.mcp.server;

import java.io.IOException;
import java.util.Map;

/**
 * Transport-neutral HTTP answer.
 *
 * @param status  the HTTP status code
 * @param body    the JSON text, or null when the response has no body (202 Accepted) or is streamed
 * @param headers extra response headers such as WWW-Authenticate, never null
 * @param stream  non-null for a Server-Sent Events response: the transport commits the status and headers, then calls it to
 *                produce the events; null for an ordinary response
 */
public record McpHttpResponse(int status, String body, Map<String, String> headers, Streaming stream) {

    /** Receives the events of a streamed response. */
    @FunctionalInterface
    public interface EventSink {

        /**
         * Sends one {@code message} event carrying this JSON text and flushes it to the client.
         *
         * @throws IOException if the client is gone
         */
        void send(String json) throws IOException;
    }

    /** Produces the events of a streamed response. It runs after the response headers are sent, on the request thread. */
    @FunctionalInterface
    public interface Streaming {

        void writeTo(EventSink sink) throws IOException;
    }


    public McpHttpResponse {
        headers = Map.copyOf(headers);
    }

    public McpHttpResponse(int status, String body, Map<String, String> headers) {
        this(status, body, headers, null);
    }

    public McpHttpResponse(int status, String body) {
        this(status, body, Map.of(), null);
    }

    /** A 200 response that streams events. */
    public static McpHttpResponse streaming(Streaming stream) {
        return new McpHttpResponse(200, null, Map.of(), stream);
    }

    public boolean isStreaming() {
        return stream != null;
    }
}
