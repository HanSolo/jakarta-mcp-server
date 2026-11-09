package eu.hansolo.mcp.api;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@code inputRequests} of a Multi Round-Trip Request: what the server needs from the client before it can finish. The keys are
 * chosen by the tool and come back as the keys of {@code inputResponses} on the retry.
 *
 * <pre>{@code
 * return ToolResult.ask(InputRequests.of("contact", Elicitation.form("Where can we reach you?").string("email", p -> p.format("email").required())), null);
 * }</pre>
 */
public final class InputRequests {

    private final Map<String, Elicitation.Request> requests = new LinkedHashMap<>();


    private InputRequests() {
    }


    /** Starts with one request. */
    public static InputRequests of(String key, Elicitation.Request request) {
        return new InputRequests().add(key, request);
    }

    /** Adds a request.
     *
     * @throws IllegalArgumentException if the key is blank or already used
     */
    public InputRequests add(String key, Elicitation.Request request) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("An input request needs a key");
        }
        if (request == null) {
            throw new IllegalArgumentException("Input request '" + key + "' is null");
        }
        if (requests.putIfAbsent(key, request) != null) {
            throw new IllegalArgumentException("Duplicate input request key '" + key + "'");
        }
        return this;
    }

    /** The keys, in the order they were added. */
    public java.util.Set<String> keys() {
        return java.util.Collections.unmodifiableSet(requests.keySet());
    }

    /** The {@code inputRequests} object. */
    public JsonObject toJson() {
        JsonObjectBuilder json = Json.createObjectBuilder();
        requests.forEach((key, request) -> json.add(key, request.toJson()));
        return json.build();
    }
}
