package eu.hansolo.mcp.api;

import java.util.List;
import java.util.Set;

/**
 * A prompt template that users pick explicitly (typically as a slash command). Implement it in a CDI bean, or use
 * {@link McpPrompt} on a method. Handlers must not keep state between calls.
 */
public interface McpPromptHandler {

    /** Unique name, 1-128 characters from {@code A-Z a-z 0-9 _ - .} (it travels in the Mcp-Name header). */
    String name();

    default String title() {
        return null;
    }

    default String description() {
        return null;
    }

    default List<PromptArgument> arguments() {
        return List.of();
    }

    /** OAuth scopes the caller needs to list and get this prompt. Requires {@code mcp.auth.enabled=true} when non-empty. */
    default Set<String> requiredScopes() {
        return Set.of();
    }

    PromptResult get(PromptCall call) throws Exception;
}
