package eu.hansolo.mcp.auth;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import eu.hansolo.mcp.api.Caller;

import java.time.Clock;

/**
 * Default authenticator: {@code Authorization: Bearer <token>}. A JWT is verified locally by {@link JwtVerifier}; if
 * {@code mcp.auth.introspection-uri} is set, every token is instead sent to the authorization server's introspection endpoint
 * (RFC 7662) by {@link IntrospectionVerifier}, which is what opaque access tokens need.
 */
@Singleton
public class BearerJwtAuthenticator implements McpAuthenticator {

    private final TokenVerifier verifier;


    @Inject
    public BearerJwtAuthenticator(AuthConfig config, KeyResolver keys) {
        this.verifier = config.introspectionUri() != null ? new IntrospectionVerifier(config, Clock.systemUTC()) : new JwtVerifier(config, keys, Clock.systemUTC());
    }


    @Override
    public Caller authenticate(String authorizationHeader) throws TokenException {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            throw new TokenException(null);
        }
        String header = authorizationHeader.trim();
        if (header.length() < 8 || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new TokenException("Unsupported authorization scheme");
        }
        String token = header.substring(7).trim();
        if (token.isEmpty()) {
            throw new TokenException("Empty bearer token");
        }
        return verifier.verify(token);
    }
}
