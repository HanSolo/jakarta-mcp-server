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
 * @param trace              the OpenTelemetry trace context of the request, never null
 */
public record ResourceCall(String uri, Map<String, String> variables, JsonObject clientCapabilities, JsonObject clientInfo, JsonObject inputResponses, String requestState, Caller caller, TraceContext trace) {

    public ResourceCall {
        variables = Map.copyOf(variables);
        trace     = trace == null ? TraceContext.EMPTY : trace;
    }

    /** A call without trace context. */
    public ResourceCall(String uri, Map<String, String> variables, JsonObject clientCapabilities, JsonObject clientInfo, JsonObject inputResponses, String requestState, Caller caller) {
        this(uri, variables, clientCapabilities, clientInfo, inputResponses, requestState, caller, TraceContext.EMPTY);
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

    public Optional<String> variable(String name) {
        return Optional.ofNullable(variables.get(name));
    }
}
