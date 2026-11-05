package eu.hansolo.mcp.server;

import java.util.Map;

/**
 * Transport-neutral HTTP answer.
 *
 * @param status  the HTTP status code
 * @param body    the JSON text, or null when the response has no body (202 Accepted)
 * @param headers extra response headers such as WWW-Authenticate, never null
 */
public record McpHttpResponse(int status, String body, Map<String, String> headers) {

    public McpHttpResponse {
        headers = Map.copyOf(headers);
    }

    public McpHttpResponse(int status, String body) {
        this(status, body, Map.of());
    }
}
