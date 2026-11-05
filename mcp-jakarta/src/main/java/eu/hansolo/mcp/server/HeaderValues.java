package eu.hansolo.mcp.server;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Decoding of the Mcp-Name header, which carries non-ASCII or unsafe values as {@code =?base64?...?=}. */
final class HeaderValues {

    private static final String PREFIX = "=?base64?";
    private static final String SUFFIX = "?=";


    private HeaderValues() {
    }


    /**
     * Returns the decoded value.
     *
     * @throws IllegalArgumentException if the value uses the base64 sentinel but is not valid base64
     */
    static String decode(String headerValue) {
        if (headerValue.length() >= PREFIX.length() + SUFFIX.length() && headerValue.startsWith(PREFIX) && headerValue.endsWith(SUFFIX)) {
            String encoded = headerValue.substring(PREFIX.length(), headerValue.length() - SUFFIX.length());
            return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
        }
        return headerValue;
    }
}
