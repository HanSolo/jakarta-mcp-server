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
     * @throws IllegalArgumentException if the value uses the base64 sentinel but is not valid, padded base64
     */
    static String decode(String headerValue) {
        if (headerValue.length() >= PREFIX.length() + SUFFIX.length() && headerValue.startsWith(PREFIX) && headerValue.endsWith(SUFFIX)) {
            String encoded = headerValue.substring(PREFIX.length(), headerValue.length() - SUFFIX.length());
            if (encoded.length() % 4 != 0) {
                // the JDK decoder accepts missing padding; the specification's test-case table says the server must reject it
                throw new IllegalArgumentException("base64 value is not padded to a multiple of four characters");
            }
            return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
        }
        return headerValue;
    }

    /**
     * True if an {@code Accept} header lists {@code text/event-stream} explicitly with a quality above zero. Wildcards do not
     * count: a client that wants a stream says so, and a plain {@code *}{@code /*} must keep getting plain JSON.
     */
    static boolean acceptsEventStream(String accept) {
        if (accept == null) {
            return false;
        }
        for (String part : accept.split(",")) {
            String[] fields = part.trim().split(";");
            if (!fields[0].trim().equalsIgnoreCase("text/event-stream")) {
                continue;
            }
            boolean refused = false;
            for (int i = 1; i < fields.length; i++) {
                String parameter = fields[i].trim().replace(" ", "");
                if (parameter.regionMatches(true, 0, "q=", 0, 2)) {
                    try {
                        refused = Double.parseDouble(parameter.substring(2)) <= 0;
                    } catch (NumberFormatException e) {
                        refused = true;
                    }
                }
            }
            if (!refused) {
                return true;
            }
        }
        return false;
    }
}
