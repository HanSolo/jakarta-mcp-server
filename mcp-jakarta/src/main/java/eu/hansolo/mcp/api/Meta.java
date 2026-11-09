package eu.hansolo.mcp.api;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonStructure;

import java.io.StringReader;
import java.util.regex.Pattern;

/**
 * Helpers for the {@code _meta} objects of tools, prompts, resources and results.
 *
 * <p>A key is {@code prefix/name} or just {@code name}. A prefix is dot separated labels (each starts with a letter, ends with a
 * letter or digit, and holds letters, digits and hyphens) followed by a slash. Prefixes whose second label is
 * {@code modelcontextprotocol} or {@code mcp} (such as {@code io.modelcontextprotocol/}) are reserved by the specification:
 * they are allowed here, because extensions such as MCP Apps define keys under them, but a client must not assume anything about
 * a reserved key the specification does not define. Use your own reverse-DNS prefix, for example {@code com.example/}.
 */
public final class Meta {

    private static final Pattern KEY = Pattern.compile("(?:[A-Za-z](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z](?:[A-Za-z0-9-]*[A-Za-z0-9])?)*/)?(?:[A-Za-z0-9](?:[A-Za-z0-9_.-]*[A-Za-z0-9])?)?");

    private Meta() {
    }


    /**
     * Checks the keys of a {@code _meta} object.
     *
     * @param meta  the object, or null (accepted)
     * @param where what it belongs to, for the message
     * @throws IllegalArgumentException if a key is not a valid {@code _meta} key
     */
    public static void validate(JsonObject meta, String where) {
        if (meta == null) {
            return;
        }
        for (String key : meta.keySet()) {
            if (!KEY.matcher(key).matches()) {
                throw new IllegalArgumentException(where + ": '" + key + "' is not a valid _meta key (use prefix/name, for example com.example/mykey)");
            }
        }
    }

    /**
     * Parses the JSON text of an annotation attribute.
     *
     * @return the object, or null for an empty text
     * @throws IllegalArgumentException if the text is not a JSON object or has an invalid key
     */
    public static JsonObject parse(String json, String where) {
        if (json == null || json.isBlank()) {
            return null;
        }
        JsonStructure value;
        try {
            value = Json.createReader(new StringReader(json)).read();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(where + ": meta is not valid JSON");
        }
        if (!(value instanceof JsonObject object)) {
            throw new IllegalArgumentException(where + ": meta must be a JSON object");
        }
        validate(object, where);
        return object;
    }
}
