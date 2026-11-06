package eu.hansolo.mcp.auth;

import eu.hansolo.mcp.api.Caller;

/** Checks one bearer token and says who it belongs to: locally for a JWT ({@link JwtVerifier}), at the authorization server for an opaque token ({@link IntrospectionVerifier}). */
public interface TokenVerifier {

    /**
     * @param token the token without the {@code Bearer} prefix
     * @throws TokenException if the token must be rejected
     */
    Caller verify(String token) throws TokenException;
}
