package eu.hansolo.mcp.api;

import jakarta.json.JsonObject;

import java.util.Set;

/**
 * A family of resources described by an RFC 6570 URI template. Supported expressions are {@code {name}} (one path segment, no
 * {@code / ? #}) and {@code {+name}} (anything, including slashes). Variable values reach {@link #read} percent-decoded and are
 * client input: a handler that maps them to files must reject path traversal itself.
 */
public interface McpResourceTemplateHandler {

    /** For example {@code greeting://{name}}. Unique across the deployment. */
    String uriTemplate();

    String name();

    default String title() {
        return null;
    }

    default String description() {
        return null;
    }

    default String mimeType() {
        return null;
    }

    default JsonObject annotations() {
        return null;
    }

    default Set<String> requiredScopes() {
        return Set.of();
    }

    /** Reads the resource at {@code call.uri()}; the template variables are in {@code call.variables()}. */
    ResourceResult read(ResourceCall call) throws Exception;
}
