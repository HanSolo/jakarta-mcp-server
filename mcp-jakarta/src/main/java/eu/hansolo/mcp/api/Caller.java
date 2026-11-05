package eu.hansolo.mcp.api;

import jakarta.json.JsonObject;

import java.util.Set;

/**
 * Who is calling, as established from the access token of this one request.
 *
 * @param subject the token's {@code sub} claim, or an empty string
 * @param scopes  the scopes the token grants
 * @param claims  all claims of the verified token (never the raw token itself, which must not be forwarded to other services)
 */
public record Caller(String subject, Set<String> scopes, JsonObject claims) {

    /** The caller when authentication is switched off. */
    public static final Caller ANONYMOUS = new Caller("", Set.of(), JsonObject.EMPTY_JSON_OBJECT);


    public Caller {
        scopes = Set.copyOf(scopes);
    }


    public boolean hasScope(String required) {
        return Scopes.satisfies(scopes, required);
    }
}
