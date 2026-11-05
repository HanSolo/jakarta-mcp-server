package eu.hansolo.mcp.api;

import java.util.Base64;
import java.util.Objects;

/**
 * One piece of resource content: exactly one of {@code text} and {@code blob} (base64) is set.
 *
 * @param uri      the URI this content belongs to (a read may return several, for example the files of a directory)
 * @param mimeType the MIME type, or null
 */
public record ResourceContent(String uri, String mimeType, String text, String blob) {

    public ResourceContent {
        Objects.requireNonNull(uri, "uri");
        if ((text == null) == (blob == null)) {
            throw new IllegalArgumentException("Exactly one of text and blob must be set");
        }
    }


    public static ResourceContent text(String uri, String mimeType, String text) {
        return new ResourceContent(uri, mimeType, text, null);
    }

    public static ResourceContent blob(String uri, String mimeType, byte[] data) {
        return new ResourceContent(uri, mimeType, null, Base64.getEncoder().encodeToString(data));
    }
}
