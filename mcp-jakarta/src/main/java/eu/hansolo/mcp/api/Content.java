package eu.hansolo.mcp.api;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * Builds the content blocks of a tool result or a prompt message: text, image, audio, {@code resource_link} and embedded
 * resources, each with optional annotations and {@code _meta}.
 *
 * <pre>{@code
 * return ToolResult.of(
 *         Content.text("Chart for March"),
 *         Content.image(png, "image/png"),
 *         Content.resourceLink("report://2026-03", "March report").mimeType("application/pdf").build());
 * }</pre>
 */
public final class Content {

    private Content() {
    }


    public static JsonObject text(String text) {
        return Json.createObjectBuilder().add("type", "text").add("text", Objects.requireNonNull(text, "text")).build();
    }

    public static JsonObject text(String text, Annotations annotations) {
        return with(Json.createObjectBuilder().add("type", "text").add("text", Objects.requireNonNull(text, "text")), annotations, null);
    }

    /** An image block; the bytes are base64 encoded here. */
    public static JsonObject image(byte[] data, String mimeType) {
        return media("image", Base64.getEncoder().encodeToString(data), mimeType, null, null);
    }

    /** An image block from data that is already base64. */
    public static JsonObject imageBase64(String base64, String mimeType) {
        return media("image", base64, mimeType, null, null);
    }

    public static JsonObject image(byte[] data, String mimeType, Annotations annotations, JsonObject meta) {
        return media("image", Base64.getEncoder().encodeToString(data), mimeType, annotations, meta);
    }

    /** An audio block; the bytes are base64 encoded here. */
    public static JsonObject audio(byte[] data, String mimeType) {
        return media("audio", Base64.getEncoder().encodeToString(data), mimeType, null, null);
    }

    public static JsonObject audioBase64(String base64, String mimeType) {
        return media("audio", base64, mimeType, null, null);
    }

    public static JsonObject audio(byte[] data, String mimeType, Annotations annotations, JsonObject meta) {
        return media("audio", Base64.getEncoder().encodeToString(data), mimeType, annotations, meta);
    }

    /** An embedded resource block: the resource's content travels inside the result. */
    public static JsonObject resource(ResourceContent content) {
        return resource(content, null, null);
    }

    public static JsonObject resource(ResourceContent content, Annotations annotations, JsonObject meta) {
        return with(Json.createObjectBuilder().add("type", "resource").add("resource", content.toJson()), annotations, meta);
    }

    /**
     * A link to a resource the client can read separately. The resource does not have to be listed by {@code resources/list}.
     *
     * @param uri  the resource URI
     * @param name a short programmatic name
     */
    public static Link resourceLink(String uri, String name) {
        return new Link(uri, name);
    }

    /** A JSON array of the blocks, for results built by hand. */
    public static JsonArray array(JsonObject... blocks) {
        JsonArrayBuilder array = Json.createArrayBuilder();
        for (JsonObject block : blocks) {
            array.add(block);
        }
        return array.build();
    }

    private static JsonObject media(String type, String base64, String mimeType, Annotations annotations, JsonObject meta) {
        Objects.requireNonNull(base64, "data");
        if (mimeType == null || mimeType.isBlank()) {
            throw new IllegalArgumentException("mimeType is required for " + type + " content");
        }
        return with(Json.createObjectBuilder().add("type", type).add("data", base64).add("mimeType", mimeType), annotations, meta);
    }

    private static JsonObject with(JsonObjectBuilder block, Annotations annotations, JsonObject meta) {
        if (annotations != null) {
            block.add("annotations", annotations.toJson());
        }
        if (meta != null) {
            Meta.validate(meta, "content _meta");
            block.add("_meta", meta);
        }
        return block.build();
    }


    /**
     * Who a content block is for and how much it matters.
     *
     * @param audience     {@code "user"}, {@code "assistant"} or both; empty means unspecified
     * @param priority     0 (optional) to 1 (effectively required), or null
     * @param lastModified when the content last changed, or null
     */
    public record Annotations(List<String> audience, Double priority, Instant lastModified) {

        public Annotations {
            audience = audience == null ? List.of() : List.copyOf(audience);
            for (String role : audience) {
                if (!"user".equals(role) && !"assistant".equals(role)) {
                    throw new IllegalArgumentException("audience entries must be \"user\" or \"assistant\"");
                }
            }
            if (priority != null && (priority < 0 || priority > 1 || priority.isNaN())) {
                throw new IllegalArgumentException("priority must be between 0 and 1");
            }
        }

        public static Annotations forAudience(String... roles) {
            return new Annotations(List.of(roles), null, null);
        }

        public Annotations withPriority(double priority) {
            return new Annotations(audience, priority, lastModified);
        }

        public Annotations withLastModified(Instant lastModified) {
            return new Annotations(audience, priority, lastModified);
        }

        public JsonObject toJson() {
            JsonObjectBuilder json = Json.createObjectBuilder();
            if (!audience.isEmpty()) {
                JsonArrayBuilder roles = Json.createArrayBuilder();
                audience.forEach(roles::add);
                json.add("audience", roles);
            }
            if (priority != null) {
                json.add("priority", priority);
            }
            if (lastModified != null) {
                json.add("lastModified", lastModified.toString());
            }
            return json.build();
        }
    }


    /** Builder for a {@code resource_link} block. */
    public static final class Link {

        private final String      uri;
        private final String      name;
        private String            title;
        private String            description;
        private String            mimeType;
        private Long              size;
        private List<Icon>        icons = List.of();
        private Annotations       annotations;
        private JsonObject        meta;


        private Link(String uri, String name) {
            this.uri  = Objects.requireNonNull(uri, "uri");
            this.name = Objects.requireNonNull(name, "name");
            if (uri.isBlank() || name.isBlank()) {
                throw new IllegalArgumentException("uri and name must not be blank");
            }
        }


        public Link title(String title) {
            this.title = title;
            return this;
        }

        public Link description(String description) {
            this.description = description;
            return this;
        }

        public Link mimeType(String mimeType) {
            this.mimeType = mimeType;
            return this;
        }

        /** Size in bytes of the raw content, if known. */
        public Link size(long size) {
            if (size < 0) {
                throw new IllegalArgumentException("size must not be negative");
            }
            this.size = size;
            return this;
        }

        public Link icons(List<Icon> icons) {
            this.icons = List.copyOf(icons);
            return this;
        }

        public Link annotations(Annotations annotations) {
            this.annotations = annotations;
            return this;
        }

        public Link meta(JsonObject meta) {
            this.meta = meta;
            return this;
        }

        public JsonObject build() {
            JsonObjectBuilder block = Json.createObjectBuilder().add("type", "resource_link").add("uri", uri).add("name", name);
            if (title != null) {
                block.add("title", title);
            }
            if (description != null) {
                block.add("description", description);
            }
            if (mimeType != null) {
                block.add("mimeType", mimeType);
            }
            if (size != null) {
                block.add("size", size);
            }
            if (!icons.isEmpty()) {
                block.add("icons", Icon.toJson(icons));
            }
            return with(block, annotations, meta);
        }
    }
}
