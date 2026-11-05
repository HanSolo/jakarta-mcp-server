package eu.hansolo.mcp.auth;

import jakarta.inject.Singleton;
import eu.hansolo.mcp.server.Settings;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * OAuth 2.1 resource-server settings. Authentication is OFF unless {@code mcp.auth.enabled=true}; when on, a misconfiguration
 * fails the deployment instead of silently serving unprotected.
 *
 * <ul>
 *   <li>{@code mcp.auth.enabled}: default false.</li>
 *   <li>{@code mcp.auth.resource}: canonical URI of the MCP endpoint as clients see it, for example {@code https://mcp.example.com/mcp}. Used as the expected token audience and in the metadata document.</li>
 *   <li>{@code mcp.auth.issuer}: the {@code iss} value tokens must carry.</li>
 *   <li>{@code mcp.auth.jwks-uri}: where the issuer's signing keys are published. Must be https unless {@code mcp.auth.jwks-allow-http=true}.</li>
 *   <li>{@code mcp.auth.audience}: expected {@code aud}, default the resource URI.</li>
 *   <li>{@code mcp.auth.authorization-servers}: comma-separated authorization server issuer URLs advertised to clients, default the issuer.</li>
 *   <li>{@code mcp.auth.scopes-supported}: comma-separated, advertised in the metadata document.</li>
 *   <li>{@code mcp.auth.required-scopes}: comma-separated scopes every request must carry.</li>
 *   <li>{@code mcp.auth.clock-skew-seconds} (60), {@code mcp.auth.jwks-cache-seconds} (300), {@code mcp.auth.jwks-min-refresh-seconds} (30).</li>
 * </ul>
 */
@Singleton
public class AuthConfig {

    private final boolean      enabled;
    private final String       resource;
    private final String       resourcePath;
    private final String       issuer;
    private final URI          jwksUri;
    private final String       audience;
    private final List<String> authorizationServers;
    private final List<String> scopesSupported;
    private final Set<String>  requiredScopes;
    private final Duration     clockSkew;
    private final Duration     jwksCache;
    private final Duration     jwksMinRefresh;
    private final boolean      jwksAllowHttp;


    public AuthConfig() {
        this(Settings.getBoolean("mcp.auth.enabled", false), Settings.get("mcp.auth.resource"), Settings.get("mcp.auth.issuer"), Settings.get("mcp.auth.jwks-uri"), Settings.get("mcp.auth.audience"), Settings.getList("mcp.auth.authorization-servers"), Settings.getList("mcp.auth.scopes-supported"), Settings.getList("mcp.auth.required-scopes"), Settings.getLong("mcp.auth.clock-skew-seconds", 60), Settings.getLong("mcp.auth.jwks-cache-seconds", 300), Settings.getLong("mcp.auth.jwks-min-refresh-seconds", 30), Settings.getBoolean("mcp.auth.jwks-allow-http", false));
    }

    public AuthConfig(boolean enabled, String resource, String issuer, String jwksUri, String audience, List<String> authorizationServers, List<String> scopesSupported, List<String> requiredScopes, long clockSkewSeconds, long jwksCacheSeconds, long jwksMinRefreshSeconds, boolean jwksAllowHttp) {
        this.enabled         = enabled;
        this.clockSkew       = Duration.ofSeconds(Math.max(0, clockSkewSeconds));
        this.jwksCache       = Duration.ofSeconds(Math.max(1, jwksCacheSeconds));
        this.jwksMinRefresh  = Duration.ofSeconds(Math.max(0, jwksMinRefreshSeconds));
        this.jwksAllowHttp   = jwksAllowHttp;
        this.scopesSupported = List.copyOf(scopesSupported);
        this.requiredScopes  = Set.copyOf(new LinkedHashSet<>(requiredScopes));
        if (!enabled) {
            this.resource             = resource;
            this.resourcePath         = "";
            this.issuer               = issuer;
            this.jwksUri              = null;
            this.audience             = audience;
            this.authorizationServers = List.of();
            return;
        }
        URI resourceUri = requireAbsoluteUri("mcp.auth.resource", resource, false);
        String path = resourceUri.getRawPath() == null || resourceUri.getRawPath().equals("/") ? "" : resourceUri.getRawPath();
        this.resourcePath = path;
        this.resource     = resourceUri.getScheme().toLowerCase(Locale.ROOT) + "://" + resourceUri.getRawAuthority().toLowerCase(Locale.ROOT) + path;
        if (issuer == null) {
            throw new IllegalStateException("mcp.auth.enabled=true requires mcp.auth.issuer");
        }
        this.issuer   = issuer;
        this.jwksUri  = requireAbsoluteUri("mcp.auth.jwks-uri", jwksUri, !jwksAllowHttp);
        this.audience = audience != null ? audience : this.resource;
        this.authorizationServers = authorizationServers.isEmpty() ? List.of(issuer) : List.copyOf(authorizationServers);
    }


    /** A configuration with authentication off, for tests and embedding. */
    public static AuthConfig disabled() {
        return new AuthConfig(false, null, null, null, null, List.of(), List.of(), List.of(), 60, 300, 30, false);
    }

    public boolean enabled() {
        return enabled;
    }

    /** Canonical resource URI without a trailing slash. Only meaningful when enabled. */
    public String resource() {
        return resource;
    }

    /** Path of the resource URI ("" for a root resource), used to serve the path-suffixed metadata URL. */
    public String resourcePath() {
        return resourcePath;
    }

    public String issuer() {
        return issuer;
    }

    public URI jwksUri() {
        return jwksUri;
    }

    public String audience() {
        return audience;
    }

    public List<String> authorizationServers() {
        return authorizationServers;
    }

    public List<String> scopesSupported() {
        return scopesSupported;
    }

    public Set<String> requiredScopes() {
        return requiredScopes;
    }

    public Duration clockSkew() {
        return clockSkew;
    }

    public Duration jwksCache() {
        return jwksCache;
    }

    public Duration jwksMinRefresh() {
        return jwksMinRefresh;
    }

    /**
     * The RFC 9728 metadata URL for this resource: the well-known segment is inserted between host and path, so
     * {@code https://h/mcp} yields {@code https://h/.well-known/oauth-protected-resource/mcp}.
     */
    public String metadataUrl() {
        URI uri = URI.create(resource);
        return uri.getScheme() + "://" + uri.getRawAuthority() + "/.well-known/oauth-protected-resource" + resourcePath;
    }

    private static URI requireAbsoluteUri(String setting, String value, boolean httpsOnly) {
        if (value == null) {
            throw new IllegalStateException("mcp.auth.enabled=true requires " + setting);
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(setting + " is not a valid URI: " + value);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("https") || scheme.equals("http")) || uri.getRawAuthority() == null || uri.getRawFragment() != null) {
            throw new IllegalStateException(setting + " must be an absolute http(s) URI without a fragment: " + value);
        }
        if (httpsOnly && !scheme.equals("https")) {
            throw new IllegalStateException(setting + " must use https (set mcp.auth.jwks-allow-http=true only for local testing): " + value);
        }
        return uri;
    }
}
