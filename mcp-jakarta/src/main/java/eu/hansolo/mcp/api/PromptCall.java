package eu.hansolo.mcp.api;

import jakarta.json.JsonObject;

import java.util.Map;
import java.util.Optional;

/**
 * Everything a prompt handler receives for one {@code prompts/get}. The server has already checked that required arguments are
 * present and that no unknown ones were sent.
 *
 * @param arguments the string arguments the client supplied
 * @param trace     the OpenTelemetry trace context of the request, never null
 */
public record PromptCall(String name, Map<String, String> arguments, JsonObject clientCapabilities, JsonObject clientInfo, JsonObject inputResponses, String requestState, Caller caller, TraceContext trace) {

    public PromptCall {
        arguments = Map.copyOf(arguments);
        trace     = trace == null ? TraceContext.EMPTY : trace;
    }

    /** A call without trace context. */
    public PromptCall(String name, Map<String, String> arguments, JsonObject clientCapabilities, JsonObject clientInfo, JsonObject inputResponses, String requestState, Caller caller) {
        this(name, arguments, clientCapabilities, clientInfo, inputResponses, requestState, caller, TraceContext.EMPTY);
    }


    public Optional<String> argument(String key) {
        return Optional.ofNullable(arguments.get(key));
    }

    /** True if the client can show this kind of elicitation ({@link Elicitation#supports}). Ask only for modes it declared. */
    public boolean clientSupportsElicitation(Elicitation.Mode mode) {
        return Elicitation.supports(clientCapabilities, mode);
    }

    public boolean clientSupports(String capability) {
        return clientCapabilities.containsKey(capability);
    }

    /** The client's settings for an extension it declared on this request, or empty (then fall back to core behaviour). */
    public Optional<JsonObject> clientExtension(String id) {
        return Extensions.clientSettings(clientCapabilities, id);
    }

    /** Refuses the request (error -32021) unless the client declared this extension. For extensions that are mandatory for this operation. */
    public void requireClientExtension(String id) {
        Extensions.require(clientCapabilities, id);
    }
}
