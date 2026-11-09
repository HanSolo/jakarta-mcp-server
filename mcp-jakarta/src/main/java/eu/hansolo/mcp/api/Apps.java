package eu.hansolo.mcp.api;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Helpers for the MCP Apps extension ({@code io.modelcontextprotocol/ui}): a tool names an HTML page, the client shows that page
 * in a sandboxed frame when it renders the tool's result.
 *
 * <pre>{@code
 * // 1. announce the extension: mcp.apps.enabled=true (or an McpExtension bean for Apps.EXTENSION_ID)
 * // 2. a tool that points at the page
 * @McpTool(description = "Revenue chart", meta = "{\"ui\":{\"resourceUri\":\"ui://revenue\"}}")
 * public ToolResult chart() { ... return ToolResult.text("Revenue: ..."); }     // text fallback is mandatory
 *
 * // 3. the page, as a resource
 * public class RevenuePage implements McpResourceHandler {
 *     public String uri() { return "ui://revenue"; }
 *     public String mimeType() { return Apps.MIME_TYPE; }
 *     public ResourceResult read(ResourceCall call) {
 *         return ResourceResult.of(Apps.html("ui://revenue", html, Apps.resourceMeta().connectDomains("https://api.example.com").build()));
 *     }
 * }
 * }</pre>
 *
 * <p>The page runs in the client, not here: it cannot call back into this server except through the client. What the library
 * checks is the wiring: a tool's {@code ui} binding must point to a {@code ui://} resource this server has, visibility values must
 * be {@code model} or {@code app}, and the origins in a {@code csp} must be plain origins, because clients build a
 * Content-Security-Policy from them.
 */
public final class Apps {

    public static final String EXTENSION_ID = "io.modelcontextprotocol/ui";
    public static final String MIME_TYPE    = "text/html;profile=mcp-app";
    public static final String URI_PREFIX   = "ui://";

    private static final Set<String> VISIBILITY  = Set.of("model", "app");
    private static final Set<String> PERMISSIONS = Set.of("camera", "microphone", "geolocation", "clipboardWrite");
    private static final Pattern     ORIGIN      = Pattern.compile("(?:(?:https?|wss?)://(?:\\*\\.)?[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?(?::[0-9]{1,5})?|data:|blob:)");
    private static final Pattern     DOMAIN      = Pattern.compile("[A-Za-z0-9._:/-]{1,253}");

    private Apps() {
    }


    /** True if the client declared the extension on this request and listed {@link #MIME_TYPE} among its {@code mimeTypes}. */
    public static boolean clientSupports(JsonObject clientCapabilities) {
        Optional<JsonObject> settings = Extensions.clientSettings(clientCapabilities, EXTENSION_ID);
        return settings.isPresent() && settings.get().get("mimeTypes") instanceof JsonArray types && types.stream().anyMatch(t -> t instanceof JsonString s && MIME_TYPE.equals(s.getString()));
    }

    public static boolean isUiUri(String uri) {
        return uri != null && uri.startsWith(URI_PREFIX) && uri.length() > URI_PREFIX.length();
    }

    /**
     * The {@code _meta} for a tool that renders in a page.
     *
     * @param resourceUri a {@code ui://} URI of a resource of this server
     * @param visibility  {@code "model"} and/or {@code "app"}; none means both (the default)
     */
    public static JsonObject toolMeta(String resourceUri, String... visibility) {
        if (!isUiUri(resourceUri)) {
            throw new IllegalArgumentException("resourceUri must be a ui:// URI");
        }
        JsonObjectBuilder ui = Json.createObjectBuilder().add("resourceUri", resourceUri);
        if (visibility.length > 0) {
            JsonArrayBuilder values = Json.createArrayBuilder();
            for (String value : new LinkedHashSet<>(List.of(visibility))) {
                if (!VISIBILITY.contains(value)) {
                    throw new IllegalArgumentException("visibility must be \"model\" or \"app\", not '" + value + "'");
                }
                values.add(value);
            }
            ui.add("visibility", values);
        }
        return Json.createObjectBuilder().add("ui", ui).build();
    }

    /** The {@code ui://} URI a tool's {@code _meta} points at, if it has such a binding. */
    public static Optional<String> boundResource(JsonObject toolMeta) {
        if (toolMeta != null && toolMeta.get("ui") instanceof JsonObject ui && ui.get("resourceUri") instanceof JsonString uri) {
            return Optional.of(uri.getString());
        }
        return Optional.empty();
    }

    /**
     * Checks the {@code ui} part of a tool's {@code _meta}.
     *
     * @throws IllegalArgumentException if {@code resourceUri} is not a ui:// URI or {@code visibility} is malformed
     */
    public static void validateToolMeta(JsonObject toolMeta, String where) {
        if (toolMeta == null || !(toolMeta.get("ui") instanceof JsonObject ui)) {
            return;
        }
        if (ui.containsKey("resourceUri") && !(ui.get("resourceUri") instanceof JsonString uri && isUiUri(uri.getString()))) {
            throw new IllegalArgumentException(where + ": _meta.ui.resourceUri must be a ui:// URI");
        }
        JsonValue visibility = ui.get("visibility");
        if (visibility != null) {
            if (!(visibility instanceof JsonArray values) || values.isEmpty()) {
                throw new IllegalArgumentException(where + ": _meta.ui.visibility must be a non-empty array of \"model\" and/or \"app\"");
            }
            for (JsonValue value : values) {
                if (!(value instanceof JsonString s) || !VISIBILITY.contains(s.getString())) {
                    throw new IllegalArgumentException(where + ": _meta.ui.visibility entries must be \"model\" or \"app\"");
                }
            }
        }
    }

    /** The page as resource content: {@link #MIME_TYPE}, with the given {@code _meta} (from {@link #resourceMeta()}), or none. */
    public static ResourceContent html(String uri, String html, JsonObject meta) {
        if (!isUiUri(uri)) {
            throw new IllegalArgumentException("uri must be a ui:// URI");
        }
        return ResourceContent.text(uri, MIME_TYPE, html).withMeta(meta);
    }

    public static ResourceMeta resourceMeta() {
        return new ResourceMeta();
    }


    /** Builder for the {@code _meta.ui} of a page: the origins it may contact, the browser features it asks for, and presentation hints. */
    public static final class ResourceMeta {

        private final JsonObjectBuilder csp         = Json.createObjectBuilder();
        private final JsonObjectBuilder permissions = Json.createObjectBuilder();
        private boolean                 hasCsp;
        private boolean                 hasPermissions;
        private String                  domain;
        private Boolean                 prefersBorder;


        private ResourceMeta() {
        }


        /** Origins the page may connect to ({@code connect-src}): fetch, XHR, WebSocket. None by default. */
        public ResourceMeta connectDomains(String... origins) {
            return origins("connectDomains", origins);
        }

        /** Origins for images, scripts, styles, fonts and media. None by default. */
        public ResourceMeta resourceDomains(String... origins) {
            return origins("resourceDomains", origins);
        }

        public ResourceMeta frameDomains(String... origins) {
            return origins("frameDomains", origins);
        }

        public ResourceMeta baseUriDomains(String... origins) {
            return origins("baseUriDomains", origins);
        }

        /** Asks for a browser feature: {@code camera}, {@code microphone}, {@code geolocation} or {@code clipboardWrite}. Clients may refuse. */
        public ResourceMeta permission(String name) {
            if (!PERMISSIONS.contains(name)) {
                throw new IllegalArgumentException("permission must be one of " + new java.util.TreeSet<>(PERMISSIONS));
            }
            permissions.add(name, Json.createObjectBuilder());
            hasPermissions = true;
            return this;
        }

        /** A dedicated sandbox origin; the format is defined by the client, so check its documentation. */
        public ResourceMeta domain(String domain) {
            if (domain == null || !DOMAIN.matcher(domain).matches()) {
                throw new IllegalArgumentException("domain must be 1-253 characters from A-Z a-z 0-9 . _ : / -");
            }
            this.domain = domain;
            return this;
        }

        public ResourceMeta prefersBorder(boolean prefersBorder) {
            this.prefersBorder = prefersBorder;
            return this;
        }

        public JsonObject build() {
            JsonObjectBuilder ui = Json.createObjectBuilder();
            if (hasCsp) {
                ui.add("csp", csp);
            }
            if (hasPermissions) {
                ui.add("permissions", permissions);
            }
            if (domain != null) {
                ui.add("domain", domain);
            }
            if (prefersBorder != null) {
                ui.add("prefersBorder", prefersBorder);
            }
            return Json.createObjectBuilder().add("ui", ui).build();
        }

        private ResourceMeta origins(String key, String[] values) {
            JsonArrayBuilder array = Json.createArrayBuilder();
            for (String value : values) {
                if (value == null || !ORIGIN.matcher(value).matches()) {
                    throw new IllegalArgumentException("'" + value + "' is not an origin: use https://host[:port], wss://host, a wildcard such as https://*.example.com, data: or blob:");
                }
                array.add(value);
            }
            csp.add(key, array);
            hasCsp = true;
            return this;
        }
    }
}
