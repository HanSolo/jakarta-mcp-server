package eu.hansolo.mcp.api;

import jakarta.json.JsonObject;

import java.util.Map;

/**
 * Everything a completion handler receives for one {@code completion/complete}. All of it is client input.
 *
 * @param kind             what is being completed
 * @param target           the prompt name or the URI template
 * @param argument         the argument (prompt) or template variable being completed
 * @param value            what the user has typed so far, possibly empty
 * @param contextArguments values the user already chose for the other arguments, possibly empty
 */
public record CompletionCall(McpCompletionHandler.Kind kind, String target, String argument, String value, Map<String, String> contextArguments, JsonObject clientCapabilities, JsonObject clientInfo, Caller caller) {

    public CompletionCall {
        contextArguments = Map.copyOf(contextArguments);
    }
}
