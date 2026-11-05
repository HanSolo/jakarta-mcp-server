package eu.hansolo.mcp.api;

import jakarta.json.JsonObject;

import java.util.Set;

/**
 * A fixed resource with one URI. Implement it in a CDI bean, or use {@link McpResource} on a method. For many resources under a
 * pattern, see {@link McpResourceTemplateHandler}. Handlers must not keep state between reads.
 */
public interface McpResourceHandler {

    /** The absolute URI (scheme required) that identifies this resource. Unique across the deployment. */
    String uri();

    /** A short programmatic name. */
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

    /** Size in bytes, if known, or null. */
    default Long size() {
        return null;
    }

    /** Resource annotations ({@code audience}, {@code priority}, {@code lastModified}), or null. */
    default JsonObject annotations() {
        return null;
    }

    /** OAuth scopes the caller needs to list and read this resource. Requires {@code mcp.auth.enabled=true} when non-empty. */
    default Set<String> requiredScopes() {
        return Set.of();
    }

    /**
     * Reads the resource. Throw {@link eu.hansolo.mcp.protocol.McpProtocolException} for protocol errors; any other exception is
     * logged and reported as an internal error.
     */
    ResourceResult read(ResourceCall call) throws Exception;
}
