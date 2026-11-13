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
 *   <li>{@code mcp.auth.introspection-uri}: use RFC 7662 token introspection instead of JWT verification, for opaque access tokens. Then {@code mcp.auth.jwks-uri} must not be set. Must be https unless {@code mcp.auth.introspection-allow-http=true}.</li>
 *   <li>{@code mcp.auth.introspection-client-id}, {@code mcp.auth.introspection-client-secret}: this server's credentials at the endpoint (HTTP Basic), if it requires them.</li>
 *   <li>{@code mcp.auth.introspection-cache-seconds}: how long an answer is reused, default 30; 0 asks for every request.</li>
 *   <li>{@code mcp.auth.introspection-require-audience}: default true; false accepts an answer without {@code aud} (a wrong {@code aud} is always refused).</li>
 *   <li>{@code mcp.auth.clock-skew-seconds} (60), {@code mcp.auth.jwks-cache-seconds} (300), {@code mcp.auth.jwks-min-refresh-seconds} (30).</li>
 * </ul>
 *
 * <p>The settings this class reads ({@code mcp.auth.*}) are the stable contract. {@link #AuthConfig()} and {@link #disabled()} are stable
 * too; the constructors with arguments exist for tests and can change in any release, so an application configures authentication through settings.
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
    private final URI          introspectionUri;
    private final String       introspectionClientId;
    private final String       introspectionClientSecret;
    private final Duration     introspectionCache;
    private final boolean      introspectionRequireAudience;


    public AuthConfig() {
        this(Settings.getBoolean("mcp.auth.enabled", false), Settings.get("mcp.auth.resource"), Settings.get("mcp.auth.issuer"), Settings.get("mcp.auth.jwks-uri"), Settings.get("mcp.auth.audience"), Settings.getList("mcp.auth.authorization-servers"), Settings.getList("mcp.auth.scopes-supported"), Settings.getList("mcp.auth.required-scopes"), Settings.getLong("mcp.auth.clock-skew-seconds", 60), Settings.getLong("mcp.auth.jwks-cache-seconds", 300), Settings.getLong("mcp.auth.jwks-min-refresh-seconds", 30), Settings.getBoolean("mcp.auth.jwks-allow-http", false), Settings.get("mcp.auth.introspection-uri"), Settings.get("mcp.auth.introspection-client-id"), Settings.get("mcp.auth.introspection-client-secret"), Settings.getLong("mcp.auth.introspection-cache-seconds", 30), Settings.getBoolean("mcp.auth.introspection-require-audience", true), Settings.getBoolean("mcp.auth.introspection-allow-http", false));
    }

    public AuthConfig(boolean enabled, String resource, String issuer, String jwksUri, String audience, List<String> authorizationServers, List<String> scopesSupported, List<String> requiredScopes, long clockSkewSeconds, long jwksCacheSeconds, long jwksMinRefreshSeconds, boolean jwksAllowHttp) {
        this(enabled, resource, issuer, jwksUri, audience, authorizationServers, scopesSupported, requiredScopes, clockSkewSeconds, jwksCacheSeconds, jwksMinRefreshSeconds, jwksAllowHttp, null, null, null, 30, true, false);
    }

    public AuthConfig(boolean enabled, String resource, String issuer, String jwksUri, String audience, List<String> authorizationServers, List<String> scopesSupported, List<String> requiredScopes, long clockSkewSeconds, long jwksCacheSeconds, long jwksMinRefreshSeconds, boolean jwksAllowHttp, String introspectionUri, String introspectionClientId, String introspectionClientSecret, long introspectionCacheSeconds, boolean introspectionRequireAudience, boolean introspectionAllowHttp) {
        this.enabled         = enabled;
        this.introspectionCache           = Duration.ofSeconds(Math.max(0, introspectionCacheSeconds));
        this.introspectionRequireAudience = introspectionRequireAudience;
        this.introspectionClientId        = introspectionClientId;
        this.introspectionClientSecret    = introspectionClientSecret;
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
            this.introspectionUri     = null;
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
        if (introspectionUri != null) {
            if (jwksUri != null) {
                throw new IllegalStateException("Set either mcp.auth.jwks-uri (JWT access tokens) or mcp.auth.introspection-uri (opaque access tokens), not both");
            }
            if (introspectionClientId == null && introspectionClientSecret != null) {
                throw new IllegalStateException("mcp.auth.introspection-client-secret needs mcp.auth.introspection-client-id");
            }
            this.introspectionUri = requireAbsoluteUri("mcp.auth.introspection-uri", introspectionUri, !introspectionAllowHttp);
            this.jwksUri          = null;
        } else {
            this.introspectionUri = null;
            this.jwksUri          = requireAbsoluteUri("mcp.auth.jwks-uri", jwksUri, !jwksAllowHttp);
        }
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

    /** The RFC 7662 introspection endpoint, or null when tokens are JWTs verified locally. */
    public URI introspectionUri() {
        return introspectionUri;
    }

    /** This server's client id at the introspection endpoint, or null. */
    public String introspectionClientId() {
        return introspectionClientId;
    }

    public String introspectionClientSecret() {
        return introspectionClientSecret;
    }

    public Duration introspectionCache() {
        return introspectionCache;
    }

    public boolean introspectionRequireAudience() {
        return introspectionRequireAudience;
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
            throw new IllegalStateException(setting + " must use https (set the matching *-allow-http=true setting only for local testing): " + value);
        }
        return uri;
    }
}
