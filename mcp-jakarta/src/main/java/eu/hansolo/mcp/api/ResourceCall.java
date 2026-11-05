package eu.hansolo.mcp.api;

import jakarta.json.JsonObject;

import java.util.Map;
import java.util.Optional;

/**
 * Everything a resource handler receives for one read. Nothing here comes from an earlier request.
 *
 * @param uri                the URI the client asked for
 * @param variables          values of the URI template variables, percent-decoded; empty for fixed resources. They are client input: validate them (a path variable needs traversal checks)
 * @param clientCapabilities capabilities the client declared on this request, never null
 * @param clientInfo         self-reported client identity, or null (display and logging only)
 * @param inputResponses     answers to a previous {@code input_required} result, or null
 * @param requestState       opaque state returned earlier, or null; client-controlled, so authenticate it
 * @param caller             who is calling; {@link Caller#ANONYMOUS} when authentication is off
 */
public record ResourceCall(String uri, Map<String, String> variables, JsonObject clientCapabilities, JsonObject clientInfo, JsonObject inputResponses, String requestState, Caller caller) {

    public ResourceCall {
        variables = Map.copyOf(variables);
    }


    public boolean clientSupports(String capability) {
        return clientCapabilities.containsKey(capability);
    }

    public Optional<String> variable(String name) {
        return Optional.ofNullable(variables.get(name));
    }
}
