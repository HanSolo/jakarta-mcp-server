package eu.hansolo.mcp.auth;

import eu.hansolo.mcp.api.Caller;

/**
 * Turns the Authorization header of one request into a {@link Caller}. The default implementation validates JWT access tokens
 * locally; provide your own CDI bean (marked {@code @Alternative @Priority}) to validate opaque tokens by introspection.
 */
public interface McpAuthenticator {

    /**
     * @param authorizationHeader the raw Authorization header value, or null when absent
     * @throws TokenException when the credentials are missing, malformed, expired, for another audience, or otherwise unacceptable
     */
    Caller authenticate(String authorizationHeader) throws TokenException;
}
