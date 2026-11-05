package eu.hansolo.mcp.api;

import jakarta.json.JsonObject;

import java.util.Map;
import java.util.Optional;

/**
 * Everything a prompt handler receives for one {@code prompts/get}. The server has already checked that required arguments are
 * present and that no unknown ones were sent.
 *
 * @param arguments the string arguments the client supplied
 */
public record PromptCall(String name, Map<String, String> arguments, JsonObject clientCapabilities, JsonObject clientInfo, JsonObject inputResponses, String requestState, Caller caller) {

    public PromptCall {
        arguments = Map.copyOf(arguments);
    }


    public Optional<String> argument(String key) {
        return Optional.ofNullable(arguments.get(key));
    }

    public boolean clientSupports(String capability) {
        return clientCapabilities.containsKey(capability);
    }
}
