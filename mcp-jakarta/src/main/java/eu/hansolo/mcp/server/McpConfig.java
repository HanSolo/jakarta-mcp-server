package eu.hansolo.mcp.server;

import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Server settings. Each one is read from a system property first, then from an environment variable
 * (the property name upper-cased, with '.' and '-' replaced by '_').
 *
 * <ul>
 *   <li>{@code mcp.allowed-origins}: comma-separated browser origins that may call the endpoint, or {@code *} for any. Default: none.</li>
 *   <li>{@code mcp.max-body-bytes}: request size limit. Default 1 MiB.</li>
 *   <li>{@code mcp.cache-ttl-ms}: ttlMs hint for server/discover and tools/list. Default 60000.</li>
 *   <li>{@code mcp.page-size}: maximum items per page for the list methods; 0 (default) returns every list whole.</li>
 *   <li>{@code mcp.server.name}, {@code mcp.server.version}, {@code mcp.instructions}: identity and model guidance.</li>
 * </ul>
 */
@Singleton
public class McpConfig {

    private static final Logger LOG = Logger.getLogger(McpConfig.class.getName());

    private final Set<String> allowedOrigins;
    private final boolean     allowAnyOrigin;
    private final int         maxBodyBytes;
    private final long        cacheTtlMs;
    private final int         pageSize;
    private final String      serverName;
    private final String      serverVersion;
    private final String      instructions;


    public McpConfig() {
        this(Settings.get("mcp.allowed-origins"), Settings.getInt("mcp.max-body-bytes", 1024 * 1024), Settings.getLong("mcp.cache-ttl-ms", 60_000L), Settings.get("mcp.server.name"), Settings.get("mcp.server.version"), Settings.get("mcp.instructions"), Settings.getInt("mcp.page-size", 0));
    }

    public McpConfig(String allowedOrigins, int maxBodyBytes, long cacheTtlMs, String serverName, String serverVersion, String instructions) {
        this(allowedOrigins, maxBodyBytes, cacheTtlMs, serverName, serverVersion, instructions, 0);
    }

    public McpConfig(String allowedOrigins, int maxBodyBytes, long cacheTtlMs, String serverName, String serverVersion, String instructions, int pageSize) {
        Properties built = buildProperties();
        this.allowedOrigins = parseOrigins(allowedOrigins);
        this.allowAnyOrigin = this.allowedOrigins.contains("*");
        this.maxBodyBytes   = Math.max(1, Math.min(maxBodyBytes, 64 * 1024 * 1024));
        this.cacheTtlMs     = cacheTtlMs;
        this.pageSize       = Math.max(0, pageSize);
        this.serverName     = serverName != null && !serverName.isBlank() ? serverName : built.getProperty("name", "jakarta-mcp-server");
        this.serverVersion  = serverVersion != null && !serverVersion.isBlank() ? serverVersion : built.getProperty("version", "unknown");
        this.instructions   = instructions != null && !instructions.isBlank() ? instructions : null;
    }


    public int maxBodyBytes() {
        return maxBodyBytes;
    }

    public long cacheTtlMs() {
        return cacheTtlMs;
    }

    /** Maximum items per page of a list result, or 0 when lists are returned whole. */
    public int pageSize() {
        return pageSize;
    }

    public String serverName() {
        return serverName;
    }

    public String serverVersion() {
        return serverVersion;
    }

    /** Natural-language guidance for the model, or null. */
    public String instructions() {
        return instructions;
    }

    /** True if a request carrying this Origin header may proceed. Requests without an Origin header (non-browser clients) are always allowed. */
    public boolean isOriginAllowed(String origin) {
        if (origin == null) {
            return true;
        }
        return allowAnyOrigin || allowedOrigins.contains(normalize(origin));
    }

    private static Set<String> parseOrigins(String value) {
        Set<String> result = new LinkedHashSet<>();
        if (value != null) {
            Arrays.stream(value.split(",")).map(McpConfig::normalize).filter(s -> !s.isEmpty()).forEach(result::add);
        }
        return Set.copyOf(result);
    }

    private static String normalize(String origin) {
        String trimmed = origin.trim().toLowerCase(Locale.ROOT);
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static Properties buildProperties() {
        Properties properties = new Properties();
        try (InputStream in = McpConfig.class.getResourceAsStream("/jakarta-mcp.properties")) {
            if (in != null) {
                properties.load(in);
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not read jakarta-mcp.properties", e);
        }
        return properties;
    }
}
