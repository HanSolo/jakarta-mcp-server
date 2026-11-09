package eu.hansolo.mcp.api;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;

import java.util.Base64;
import java.util.Objects;

/**
 * One piece of resource content: exactly one of {@code text} and {@code blob} (base64) is set.
 *
 * @param uri      the URI this content belongs to (a read may return several, for example the files of a directory)
 * @param mimeType the MIME type, or null
 * @param meta     optional {@code _meta} of this content (MCP Apps put their {@code ui} settings here), or null
 */
public record ResourceContent(String uri, String mimeType, String text, String blob, JsonObject meta) {

    public ResourceContent {
        Objects.requireNonNull(uri, "uri");
        Meta.validate(meta, "resource content _meta");
        if ((text == null) == (blob == null)) {
            throw new IllegalArgumentException("Exactly one of text and blob must be set");
        }
    }


    public ResourceContent(String uri, String mimeType, String text, String blob) {
        this(uri, mimeType, text, blob, null);
    }


    public static ResourceContent text(String uri, String mimeType, String text) {
        return new ResourceContent(uri, mimeType, text, null);
    }

    public static ResourceContent blob(String uri, String mimeType, byte[] data) {
        return new ResourceContent(uri, mimeType, null, Base64.getEncoder().encodeToString(data));
    }

    /** The same content with a {@code _meta} object. */
    public ResourceContent withMeta(JsonObject meta) {
        return new ResourceContent(uri, mimeType, text, blob, meta);
    }

    /** The JSON form used in {@code resources/read} results and embedded resources. */
    public JsonObject toJson() {
        JsonObjectBuilder json = Json.createObjectBuilder().add("uri", uri);
        if (mimeType != null) {
            json.add("mimeType", mimeType);
        }
        if (text != null) {
            json.add("text", text);
        } else {
            json.add("blob", blob);
        }
        if (meta != null) {
            json.add("_meta", meta);
        }
        return json.build();
    }
}
