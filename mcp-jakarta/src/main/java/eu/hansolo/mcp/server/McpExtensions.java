package eu.hansolo.mcp.server;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import eu.hansolo.mcp.api.Apps;
import eu.hansolo.mcp.api.Extensions;
import eu.hansolo.mcp.api.McpExtension;

import java.util.Map;
import java.util.TreeMap;

/**
 * The extensions this server announces in {@code server/discover}: every {@link McpExtension} bean, checked once at startup (valid
 * identifier, no duplicates, settings are an object). With none, {@code capabilities} has no {@code extensions} field.
 *
 * <p><b>Internal.</b> Not part of the stable API: this class is public only because other packages of this library use it, and it can
 * change in any release. Applications should not call it.
 */
@Singleton
public class McpExtensions {

    private final Map<String, JsonObject> announced;


    @Inject
    public McpExtensions(Instance<McpExtension> available) {
        this(available == null ? java.util.List.<McpExtension>of() : available);
    }

    public McpExtensions(Iterable<? extends McpExtension> available) {
        Map<String, JsonObject> found = new TreeMap<>();
        for (McpExtension extension : available) {
            String id = extension.id();
            if (!Extensions.isValidId(id)) {
                throw new IllegalStateException("Invalid MCP extension id '" + id + "' on " + extension.getClass().getName() + ": use vendor-prefix/extension-name, for example com.example/reports");
            }
            JsonObject settings = extension.settings();
            if (settings == null) {
                throw new IllegalStateException("MCP extension '" + id + "': settings() must return an object (use an empty one for none)");
            }
            if (found.putIfAbsent(id, settings) != null) {
                throw new IllegalStateException("Duplicate MCP extension '" + id + "'");
            }
        }
        if (Settings.getBoolean("mcp.apps.enabled", false)) {
            found.putIfAbsent(Apps.EXTENSION_ID, Json.createObjectBuilder().build());
        }
        this.announced = Map.copyOf(found);
    }


    /** No extensions. */
    public static McpExtensions none() {
        return new McpExtensions(java.util.List.<McpExtension>of());
    }

    public boolean isEmpty() {
        return announced.isEmpty();
    }

    public boolean announces(String id) {
        return announced.containsKey(id);
    }

    /** The {@code extensions} object for {@code capabilities}, in id order. */
    public JsonObject toJson() {
        JsonObjectBuilder json = Json.createObjectBuilder();
        new TreeMap<>(announced).forEach(json::add);
        return json.build();
    }
}
