package eu.hansolo.mcp.api;

import jakarta.json.JsonObject;
import eu.hansolo.mcp.protocol.McpProtocolException;

import java.util.Optional;
import java.util.regex.Pattern;

/** What a request says about the extensions the client supports ({@code clientCapabilities.extensions}). */
public final class Extensions {

    private static final Pattern ID = Pattern.compile("[A-Za-z](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z](?:[A-Za-z0-9-]*[A-Za-z0-9])?)*/[A-Za-z0-9](?:[A-Za-z0-9_.-]*[A-Za-z0-9])?");

    private Extensions() {
    }


    /** True for {@code vendor-prefix/extension-name}, the form the specification gives extension identifiers. */
    public static boolean isValidId(String id) {
        return id != null && id.length() <= 256 && ID.matcher(id).matches();
    }

    /** The client's settings for this extension, or empty if it did not declare it. An extension declared with a non-object value counts as not declared. */
    public static Optional<JsonObject> clientSettings(JsonObject clientCapabilities, String id) {
        if (clientCapabilities != null && clientCapabilities.get("extensions") instanceof JsonObject extensions && extensions.get(id) instanceof JsonObject settings) {
            return Optional.of(settings);
        }
        return Optional.empty();
    }

    /**
     * @throws McpProtocolException with error -32021 (missing required client capability) if the client did not declare the extension
     */
    public static void require(JsonObject clientCapabilities, String id) {
        if (clientSettings(clientCapabilities, id).isEmpty()) {
            throw McpProtocolException.missingClientExtension(id);
        }
    }
}
