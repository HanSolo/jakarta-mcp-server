package eu.hansolo.mcp.auth;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import eu.hansolo.mcp.api.Caller;

import java.time.Clock;

/** Default authenticator: {@code Authorization: Bearer <JWT>} verified locally by {@link JwtVerifier}. */
@Singleton
public class BearerJwtAuthenticator implements McpAuthenticator {

    private final JwtVerifier verifier;


    @Inject
    public BearerJwtAuthenticator(AuthConfig config, KeyResolver keys) {
        this.verifier = new JwtVerifier(config, keys, Clock.systemUTC());
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
