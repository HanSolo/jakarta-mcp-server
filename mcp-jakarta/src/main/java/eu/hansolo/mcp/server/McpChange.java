package eu.hansolo.mcp.server;

import jakarta.json.Json;
import jakarta.json.JsonException;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonString;

import java.io.StringReader;
import java.util.Objects;


/**
 * One change that {@link McpNotifications} announces to listening clients, in a form that can travel between server instances
 * (see {@link McpNotificationBridge}).
 *
 * <p>{@link #encode()} and {@link #decode(String)} turn it into a short JSON text, so a bridge can send it as the body of a message,
 * a database notification or a cache event without knowing anything else about it.
 *
 * @param origin the {@link McpNotifications#instanceId()} of the instance where the change was announced
 * @param kind   what changed
 * @param uri    the resource that changed for {@link Kind#RESOURCE_UPDATED}, otherwise null
 */
public record McpChange(String origin, Kind kind, String uri) {

    /** Longest text {@link #decode(String)} accepts. The text comes from outside this JVM, so it is bounded. */
    public static final int MAX_ENCODED_LENGTH = 8192;

    private static final int VERSION = 1;


    public enum Kind {
        TOOLS_LIST_CHANGED("tools"),
        PROMPTS_LIST_CHANGED("prompts"),
        RESOURCES_LIST_CHANGED("resources"),
        RESOURCE_UPDATED("resource");

        private final String wire;

        Kind(String wire) {
            this.wire = wire;
        }

        static Kind of(String wire) {
            for (Kind kind : values()) {
                if (kind.wire.equals(wire)) {
                    return kind;
                }
            }
            throw new IllegalArgumentException("Unknown change kind");
        }
    }


    public McpChange {
        Objects.requireNonNull(kind, "kind");
        if (origin == null || origin.isBlank()) {
            throw new IllegalArgumentException("origin must not be blank");
        }
        if (kind == Kind.RESOURCE_UPDATED) {
            if (uri == null || uri.isBlank()) {
                throw new IllegalArgumentException("A resource update needs a uri");
            }
        } else if (uri != null) {
            throw new IllegalArgumentException("Only a resource update carries a uri");
        }
    }


    /** The change as one JSON text, for {@link #decode(String)} on another instance. */
    public String encode() {
        JsonObjectBuilder builder = Json.createObjectBuilder().add("v", VERSION).add("o", origin).add("k", kind.wire);
        if (uri != null) {
            builder.add("u", uri);
        }
        return builder.build().toString();
    }

    /**
     * Reads what {@link #encode()} wrote.
     *
     * @throws IllegalArgumentException if the text is not a change message of this version; nothing about the text is repeated in the message
     */
    public static McpChange decode(String text) {
        if (text == null || text.length() > MAX_ENCODED_LENGTH) {
            throw new IllegalArgumentException("Not an MCP change message");
        }
        try (var reader = Json.createReader(new StringReader(text))) {
            JsonObject object = reader.readObject();
            if (!(object.get("v") instanceof JsonNumber version) || version.intValue() != VERSION) {
                throw new IllegalArgumentException("Unsupported MCP change message version");
            }
            if (!(object.get("o") instanceof JsonString origin) || !(object.get("k") instanceof JsonString kind)) {
                throw new IllegalArgumentException("Not an MCP change message");
            }
            String uri = null;
            if (object.containsKey("u")) {
                if (!(object.get("u") instanceof JsonString value)) {
                    throw new IllegalArgumentException("Not an MCP change message");
                }
                uri = value.getString();
            }
            return new McpChange(origin.getString(), Kind.of(kind.getString()), uri);
        } catch (JsonException | IllegalStateException | ClassCastException e) {
            throw new IllegalArgumentException("Not an MCP change message");
        }
    }
}
