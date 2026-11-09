package eu.hansolo.mcp.api;

import jakarta.json.JsonObject;

import java.util.List;
import java.util.Set;

/**
 * A tool exposed over MCP. Implement this interface in a CDI bean (for example {@code @ApplicationScoped}) and the server
 * discovers it automatically through {@code Instance<McpToolHandler>}.
 *
 * <p>Implementations must be stateless between calls: the protocol has no sessions, and consecutive calls from one client
 * may be served by different server instances. State that must survive a call belongs in an explicit handle that the tool
 * returns and the model passes back as an argument.
 */
public interface McpToolHandler {

    /** Unique name, 1-128 characters from {@code A-Z a-z 0-9 _ - .} (header-safe, because it travels in the Mcp-Name header). */
    String name();

    /** Human-readable title, or null to fall back to the name. */
    default String title() {
        return null;
    }

    /** Description for the model, or null. */
    String description();

    /** JSON Schema (2020-12) for the arguments object. Use {@code {"type":"object","additionalProperties":false}} for no arguments. */
    JsonObject inputSchema();

    /** Optional JSON Schema for {@code structuredContent}, or null. */
    default JsonObject outputSchema() {
        return null;
    }

    /** Optional tool annotations (readOnlyHint, destructiveHint, ...), or null. Clients must treat them as untrusted hints. */
    default JsonObject annotations() {
        return null;
    }

    /** Icons clients may show next to the name, or an empty list. */
    default List<Icon> icons() {
        return List.of();
    }

    /** Optional {@code _meta} object listed with this tool (extensions such as MCP Apps define keys here), or null. */
    default JsonObject meta() {
        return null;
    }

    /**
     * OAuth scopes the caller's token must carry to run this tool; empty means any authenticated caller. Declaring scopes while
     * {@code mcp.auth.enabled} is false fails the deployment, because the requirement could never be enforced.
     */
    default Set<String> requiredScopes() {
        return Set.of();
    }

    /**
     * Runs the tool. Throw {@link eu.hansolo.mcp.protocol.McpProtocolException} for protocol-level errors; any other
     * exception is logged and reported to the client as a generic {@code isError} result.
     */
    ToolResult call(ToolCall call) throws Exception;
}
