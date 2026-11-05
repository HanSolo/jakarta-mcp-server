package eu.hansolo.mcp.auth;

import java.security.PublicKey;
import java.util.List;

/** Finds the public keys that may have signed a token. Implementations must never fetch anything named by the token itself. */
public interface KeyResolver {

    /**
     * @param kid the token header's {@code kid}, or null
     * @param alg the token header's {@code alg} ("RS256" or "ES256")
     * @return the keys worth trying, possibly empty
     */
    List<PublicKey> candidates(String kid, String alg);
}
