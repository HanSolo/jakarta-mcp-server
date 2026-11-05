package eu.hansolo.mcp.api;

import jakarta.json.Json;
import jakarta.json.JsonObject;

import java.util.Objects;

/**
 * One message of a prompt.
 *
 * @param role    {@code "user"} or {@code "assistant"}
 * @param content a content block: text, image, audio, {@code resource_link} or an embedded {@code resource}
 */
public record PromptMessage(String role, JsonObject content) {

    public PromptMessage {
        if (!"user".equals(role) && !"assistant".equals(role)) {
            throw new IllegalArgumentException("role must be \"user\" or \"assistant\"");
        }
        Objects.requireNonNull(content, "content");
    }


    public static PromptMessage user(String text) {
        return new PromptMessage("user", textBlock(text));
    }

    public static PromptMessage assistant(String text) {
        return new PromptMessage("assistant", textBlock(text));
    }

    /** A user message with an arbitrary content block, for example {@link #embeddedResource(ResourceContent)}. */
    public static PromptMessage user(JsonObject content) {
        return new PromptMessage("user", content);
    }

    public static JsonObject textBlock(String text) {
        return Json.createObjectBuilder().add("type", "text").add("text", text).build();
    }

    public static JsonObject embeddedResource(ResourceContent content) {
        var resource = Json.createObjectBuilder().add("uri", content.uri());
        if (content.mimeType() != null) {
            resource.add("mimeType", content.mimeType());
        }
        if (content.text() != null) {
            resource.add("text", content.text());
        } else {
            resource.add("blob", content.blob());
        }
        return Json.createObjectBuilder().add("type", "resource").add("resource", resource).build();
    }
}
