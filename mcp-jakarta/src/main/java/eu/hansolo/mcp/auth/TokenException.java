package eu.hansolo.mcp.auth;

/** A bearer token that must be rejected. The message is safe to show to the client (it names the category, never the token or key material). */
public class TokenException extends Exception {

    private static final long serialVersionUID = 1L;


    /** A token that is not acceptable; the message says why. */
    public TokenException(String message) {
        super(message);
    }
}
