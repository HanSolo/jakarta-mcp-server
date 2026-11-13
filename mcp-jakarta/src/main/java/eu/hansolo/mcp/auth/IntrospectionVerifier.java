package eu.hansolo.mcp.auth;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonException;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import eu.hansolo.mcp.api.Caller;
import eu.hansolo.mcp.protocol.ErrorCodes;
import eu.hansolo.mcp.protocol.McpProtocolException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Validates an opaque access token by asking the authorization server (RFC 7662 token introspection). The answer is trusted only
 * if the server says {@code "active": true}; then {@code iss} (if present), {@code aud} (RFC 8707 resource binding), {@code exp}
 * and {@code nbf} are checked as for a JWT. The endpoint comes from configuration only.
 *
 * <p>Answers are cached for {@code mcp.auth.introspection-cache-seconds}, keyed by a SHA-256 hash of the token (the token itself is
 * never stored), and never beyond the token's own {@code exp}. A token revoked at the server can therefore still be accepted for up to
 * that long. A refusal is cached for at most five seconds. If the endpoint cannot be reached, answers an error or sends something
 * unusable, the request fails with HTTP 503 instead of 401: the client's token may be fine, so it should not start a new login.
 *
 * <p><b>Internal.</b> Not part of the stable API: this class is public only because other packages of this library use it,
 * and it can change in any release. Applications should not call it.
 */
public final class IntrospectionVerifier implements TokenVerifier {

    private static final Logger   LOG                = Logger.getLogger(IntrospectionVerifier.class.getName());
    private static final int      MAX_TOKEN_CHARS    = 8192;
    private static final int      MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final int      MAX_CACHE_ENTRIES  = 10_000;
    private static final Duration REFUSAL_CACHE_MAX  = Duration.ofSeconds(5);

    private final AuthConfig             config;
    private final Clock                  clock;
    private final HttpClient             http;
    private final String                 basicAuthorization;
    private final Map<String, Remembered> cache = new LinkedHashMap<>(256, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Remembered> eldest) {
            return size() > MAX_CACHE_ENTRIES;
        }
    };


    public IntrospectionVerifier(AuthConfig config, Clock clock) {
        this.config = config;
        this.clock  = clock;
        this.http   = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
        if (config.introspectionClientId() != null) {
            String credentials = form(config.introspectionClientId()) + ":" + form(config.introspectionClientSecret() == null ? "" : config.introspectionClientSecret());
            this.basicAuthorization = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        } else {
            this.basicAuthorization = null;
        }
    }


    @Override
    public Caller verify(String token) throws TokenException {
        if (token.length() > MAX_TOKEN_CHARS) {
            throw new TokenException("Token too large");
        }
        String key = fingerprint(token);
        Instant now = clock.instant();
        Remembered known = recall(key, now);
        if (known != null) {
            return known.result();
        }
        JsonObject answer = ask(token);
        Remembered fresh;
        try {
            fresh = new Remembered(evaluate(answer, now), null, expiryOfPositive(answer, now));
        } catch (TokenException e) {
            fresh = new Remembered(null, e.getMessage(), now.plus(min(config.introspectionCache(), REFUSAL_CACHE_MAX)));
        }
        remember(key, fresh);
        return fresh.result();
    }

    // ---- the exchange ----------------------------------------------------------------------------------------------

    private JsonObject ask(String token) {
        String body = "token=" + form(token) + "&token_type_hint=access_token";
        HttpRequest.Builder request = HttpRequest.newBuilder(config.introspectionUri()).timeout(Duration.ofSeconds(5)).header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (basicAuthorization != null) {
            request.header("Authorization", basicAuthorization);
        }
        try {
            HttpResponse<InputStream> response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                if (response.statusCode() != 200) {
                    in.readNBytes(1024);
                    if (response.statusCode() == 401 || response.statusCode() == 403) {
                        LOG.warning("The introspection endpoint refused this server's credentials (HTTP " + response.statusCode() + "): check mcp.auth.introspection-client-id and -client-secret");
                    } else {
                        LOG.warning("The introspection endpoint answered HTTP " + response.statusCode());
                    }
                    throw unavailable();
                }
                byte[] bytes = in.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES) {
                    LOG.warning("The introspection response is larger than 1 MiB");
                    throw unavailable();
                }
                try (var reader = Json.createReader(new java.io.ByteArrayInputStream(bytes))) {
                    return reader.readObject();
                }
            }
        } catch (IOException | JsonException | IllegalStateException | ClassCastException e) {
            LOG.log(Level.WARNING, "Token introspection failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            throw unavailable();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable();
        }
    }

    private static McpProtocolException unavailable() {
        return new McpProtocolException(503, ErrorCodes.INTERNAL_ERROR, "Token introspection is unavailable", null);
    }

    // ---- the verdict -----------------------------------------------------------------------------------------------

    private Caller evaluate(JsonObject answer, Instant now) throws TokenException {
        if (answer.get("active") != JsonValue.TRUE) {
            throw new TokenException("Token is not active");
        }
        if (answer.containsKey("iss") && !(answer.get("iss") instanceof JsonString iss && iss.getString().equals(config.issuer()))) {
            throw new TokenException("Wrong issuer");
        }
        if (answer.containsKey("aud") ? !audienceMatches(answer.get("aud")) : config.introspectionRequireAudience()) {
            throw new TokenException("Token was not issued for this resource");
        }
        if (answer.get("exp") instanceof JsonNumber exp && !now.minus(config.clockSkew()).isBefore(Instant.ofEpochSecond(exp.longValue()))) {
            throw new TokenException("Token expired");
        }
        if (answer.get("nbf") instanceof JsonNumber nbf && now.plus(config.clockSkew()).isBefore(Instant.ofEpochSecond(nbf.longValue()))) {
            throw new TokenException("Token not yet valid");
        }
        return new Caller(answer.get("sub") instanceof JsonString s ? s.getString() : "", JwtVerifier.scopesOf(answer), answer);
    }

    private boolean audienceMatches(JsonValue aud) {
        if (aud instanceof JsonString s) {
            return s.getString().equals(config.audience());
        }
        if (aud instanceof JsonArray array) {
            return array.stream().anyMatch(v -> v instanceof JsonString s && s.getString().equals(config.audience()));
        }
        return false;
    }

    /** How long a positive answer may be reused: the configured time, but never past the token's own expiry. */
    private Instant expiryOfPositive(JsonObject answer, Instant now) {
        Instant until = now.plus(config.introspectionCache());
        if (answer.get("exp") instanceof JsonNumber exp) {
            Instant tokenEnd = Instant.ofEpochSecond(exp.longValue());
            if (tokenEnd.isBefore(until)) {
                until = tokenEnd;
            }
        }
        return until;
    }

    // ---- the cache -------------------------------------------------------------------------------------------------

    private synchronized Remembered recall(String key, Instant now) {
        Remembered found = cache.get(key);
        if (found == null) {
            return null;
        }
        if (!found.until().isAfter(now)) {
            cache.remove(key);
            return null;
        }
        return found;
    }

    private synchronized void remember(String key, Remembered value) {
        if (value.until().isAfter(clock.instant())) {
            cache.put(key, value);
        }
    }

    private static String fingerprint(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    private static String form(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }


    /** What was learned about one token: who it is, or why it was refused. */
    private record Remembered(Caller caller, String refusal, Instant until) {

        Caller result() throws TokenException {
            if (caller == null) {
                throw new TokenException(refusal);
            }
            return caller;
        }
    }
}
