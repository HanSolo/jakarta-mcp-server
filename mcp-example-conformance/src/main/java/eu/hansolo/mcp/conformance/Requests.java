package eu.hansolo.mcp.conformance;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import java.util.Optional;

/** Builds the server-to-client requests of Multi Round-Trip Requests and reads the answers. */
final class Requests {

    private Requests() {
    }


    /** An elicitation asking for one property of the given JSON Schema type. */
    static JsonObject elicit(String message, String property, String type) {
        JsonObject schema = Json.createObjectBuilder().add("type", "object").add("properties", Json.createObjectBuilder().add(property, Json.createObjectBuilder().add("type", type))).add("required", Json.createArrayBuilder().add(property)).build();
        JsonObject params = Json.createObjectBuilder().add("mode", "form").add("message", message).add("requestedSchema", schema).build();
        return Json.createObjectBuilder().add("method", "elicitation/create").add("params", params).build();
    }

    static JsonObject sample(String prompt, int maxTokens) {
        JsonObject message = Json.createObjectBuilder().add("role", "user").add("content", Content.text(prompt)).build();
        JsonObject params  = Json.createObjectBuilder().add("messages", Json.createArrayBuilder().add(message)).add("maxTokens", maxTokens).build();
        return Json.createObjectBuilder().add("method", "sampling/createMessage").add("params", params).build();
    }

    static JsonObject roots() {
        return Json.createObjectBuilder().add("method", "roots/list").add("params", Json.createObjectBuilder()).build();
    }

    static JsonObject requests(String key, JsonObject request) {
        return Json.createObjectBuilder().add(key, request).build();
    }

    /** The content of an accepted elicitation answer, or empty if the key is missing, declined or cancelled. */
    static Optional<JsonObject> accepted(JsonObject responses, String key) {
        if (responses != null && responses.get(key) instanceof JsonObject answer && answer.get("action") instanceof JsonString action && action.getString().equals("accept") && answer.get("content") instanceof JsonObject content) {
            return Optional.of(content);
        }
        return Optional.empty();
    }

    /** A string property of an accepted elicitation answer. */
    static Optional<String> acceptedString(JsonObject responses, String key, String property) {
        return accepted(responses, key).map(c -> c.get(property)).filter(JsonString.class::isInstance).map(v -> ((JsonString) v).getString());
    }

    /** The text of a sampling answer: {@code content.text}, or a plain {@code text}. */
    static Optional<String> sampledText(JsonObject responses, String key) {
        if (responses != null && responses.get(key) instanceof JsonObject answer) {
            JsonValue content = answer.get("content");
            if (content instanceof JsonObject block && block.get("text") instanceof JsonString text) {
                return Optional.of(text.getString());
            }
            if (answer.get("text") instanceof JsonString text) {
                return Optional.of(text.getString());
            }
        }
        return Optional.empty();
    }

    /** The number of roots in a {@code roots/list} answer, or empty if the key is missing. */
    static Optional<Integer> rootCount(JsonObject responses, String key) {
        if (responses != null && responses.get(key) instanceof JsonObject answer) {
            if (answer.get("roots") instanceof JsonArray roots) {
                return Optional.of(roots.size());
            }
            return Optional.of(0);
        }
        return Optional.empty();
    }
}
