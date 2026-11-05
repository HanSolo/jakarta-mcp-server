package eu.hansolo.mcp.api;

import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import java.util.Optional;

/**
 * Everything a tool receives for one call. Nothing here comes from an earlier request.
 *
 * @param name               the tool name
 * @param arguments          validated arguments, never null
 * @param clientCapabilities capabilities the client declared on this request, never null
 * @param clientInfo         self-reported client identity, or null (display and logging only, never a security decision)
 * @param inputResponses     answers to a previous {@code input_required} result (Multi Round-Trip Requests), or null
 * @param requestState       opaque state the tool returned earlier, or null; it is client-controlled, so authenticate it before trusting it
 * @param caller             who is calling, from this request's access token; {@link Caller#ANONYMOUS} when authentication is off
 */
public record ToolCall(String name, JsonObject arguments, JsonObject clientCapabilities, JsonObject clientInfo, JsonObject inputResponses, String requestState, Caller caller) {

    public boolean clientSupports(String capability) {
        return clientCapabilities.containsKey(capability);
    }

    /** The string argument with this name, if present and a string. */
    public Optional<String> stringArgument(String key) {
        return arguments.get(key) instanceof JsonString s ? Optional.of(s.getString()) : Optional.empty();
    }

    /** The client's answer to the input request with this key, if the call is a retry that carries one. */
    public Optional<JsonObject> inputResponse(String key) {
        if (inputResponses == null) {
            return Optional.empty();
        }
        JsonValue value = inputResponses.get(key);
        return value instanceof JsonObject o ? Optional.of(o) : Optional.empty();
    }
}
