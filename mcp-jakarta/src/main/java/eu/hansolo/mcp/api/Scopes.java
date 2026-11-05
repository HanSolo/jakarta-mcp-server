package eu.hansolo.mcp.api;

import java.util.Set;

/**
 * OAuth scope matching with the one hierarchy this server understands: a broader scope implies the narrower scopes below it,
 * separated by a colon. A token with {@code files} satisfies {@code files:read}; a token with {@code files:read} does not
 * satisfy {@code files}.
 */
public final class Scopes {

    private Scopes() {
    }


    public static boolean satisfies(Set<String> granted, String required) {
        for (String scope : granted) {
            if (scope.equals(required) || required.startsWith(scope + ":")) {
                return true;
            }
        }
        return false;
    }

    public static boolean satisfiesAll(Set<String> granted, Set<String> required) {
        return required.stream().allMatch(r -> satisfies(granted, r));
    }
}
