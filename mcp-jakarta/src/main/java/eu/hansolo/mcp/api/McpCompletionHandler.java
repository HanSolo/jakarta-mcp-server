package eu.hansolo.mcp.api;

import java.util.Set;

/**
 * Suggests values for one argument of one prompt or one variable of one resource template. Implement it in a CDI bean, or use
 * {@link McpComplete} on a method. Arguments without a handler complete to an empty list. The server checks at startup that the
 * target and argument exist.
 */
public interface McpCompletionHandler {

    /** What a handler completes for. */
    enum Kind {
        PROMPT, RESOURCE_TEMPLATE
    }


    Kind kind();

    /** The prompt name, or the resource template string exactly as registered (for example {@code greeting://{name}}). */
    String target();

    /** The prompt argument or template variable. */
    String argument();

    /** Extra scopes needed for the suggestions themselves (the scopes of the prompt or template always apply). */
    default Set<String> requiredScopes() {
        return Set.of();
    }

    /** Never suggest anything the caller may not see: suggestions can leak data as surely as a read can. */
    CompletionResult complete(CompletionCall call) throws Exception;
}
