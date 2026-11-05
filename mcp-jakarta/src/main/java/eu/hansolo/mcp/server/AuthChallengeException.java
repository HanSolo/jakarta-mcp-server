package eu.hansolo.mcp.server;

/** Internal signal from deep inside request handling that the answer must be a 403 insufficient_scope challenge. */
final class AuthChallengeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String scope;


    AuthChallengeException(String message, String scope) {
        super(message, null, false, false);
        this.scope = scope;
    }


    String scope() {
        return scope;
    }
}
