package eu.hansolo.mcp.auth;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Keys from the issuer's JWKS endpoint ({@code mcp.auth.jwks-uri}). The document is cached; an unknown {@code kid} triggers at
 * most one refresh per {@code mcp.auth.jwks-min-refresh-seconds}, so key rotation works without letting garbage tokens turn this
 * server into a request amplifier. If a refresh fails the previous keys stay in use. The URL comes from configuration only.
 *
 * <p><b>Internal.</b> Not part of the stable API: this class is public only because other packages of this library use it,
 * and it can change in any release. Applications should not call it.
 */
@Singleton
public class JwksKeyResolver implements KeyResolver {

    private static final Logger LOG = Logger.getLogger(JwksKeyResolver.class.getName());

    private final AuthConfig config;
    private final Clock      clock;
    private final HttpClient http;
    private List<Jwk>        cached        = List.of();
    private Instant          fetchedAt     = Instant.MIN;
    private Instant          lastAttemptAt = Instant.MIN;


    @Inject
    public JwksKeyResolver(AuthConfig config) {
        this(config, Clock.systemUTC());
    }

    JwksKeyResolver(AuthConfig config, Clock clock) {
        this.config = config;
        this.clock  = clock;
        this.http   = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    }


    @Override
    public synchronized List<PublicKey> candidates(String kid, String alg) {
        Instant now = clock.instant();
        boolean expired = fetchedAt.plus(config.jwksCache()).isBefore(now);
        if (expired && attemptAllowed(now)) {
            refresh(now);
        }
        List<PublicKey> found = match(kid, alg);
        if (found.isEmpty() && !expired && attemptAllowed(now)) {
            refresh(now);
            found = match(kid, alg);
        }
        return found;
    }

    private boolean attemptAllowed(Instant now) {
        return config.jwksUri() != null && !lastAttemptAt.plus(config.jwksMinRefresh()).isAfter(now);
    }

    private List<PublicKey> match(String kid, String alg) {
        String kty = alg.equals("RS256") ? "RSA" : "EC";
        List<PublicKey> found = new ArrayList<>();
        for (Jwk jwk : cached) {
            if (jwk.type().equals(kty) && (kid == null || kid.equals(jwk.kid()))) {
                found.add(jwk.key());
            }
        }
        return found;
    }

    private void refresh(Instant now) {
        lastAttemptAt = now;
        try {
            HttpRequest request = HttpRequest.newBuilder(config.jwksUri()).timeout(Duration.ofSeconds(5)).header("Accept", "application/json").GET().build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                if (response.statusCode() != 200) {
                    LOG.warning("JWKS endpoint answered HTTP " + response.statusCode() + "; keeping the previous keys");
                    return;
                }
                byte[] bytes = in.readNBytes(1024 * 1024 + 1);
                if (bytes.length > 1024 * 1024) {
                    LOG.warning("JWKS document is larger than 1 MiB; ignoring it");
                    return;
                }
                cached    = parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                fetchedAt = now;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Could not refresh the JWKS from " + config.jwksUri() + "; keeping the previous keys", e);
        }
    }

    /** Parses a JWK Set, skipping keys that are unusable (wrong use, unsupported type or curve, malformed). */
    static List<Jwk> parse(String json) {
        List<Jwk> keys = new ArrayList<>();
        try (var reader = Json.createReader(new java.io.StringReader(json))) {
            JsonArray array = reader.readObject().getJsonArray("keys");
            if (array == null) {
                return keys;
            }
            for (JsonValue value : array) {
                if (value instanceof JsonObject jwk) {
                    try {
                        toKey(jwk, keys);
                    } catch (GeneralSecurityException | RuntimeException e) {
                        LOG.fine("Skipping unusable JWK: " + e.getMessage());
                    }
                }
            }
        }
        return keys;
    }

    private static void toKey(JsonObject jwk, List<Jwk> into) throws GeneralSecurityException {
        String kty = string(jwk, "kty");
        String use = string(jwk, "use");
        if ("enc".equals(use)) {
            return;
        }
        String kid = string(jwk, "kid");
        if ("RSA".equals(kty)) {
            BigInteger n = big(jwk, "n");
            BigInteger e = big(jwk, "e");
            if (n.bitLength() < 2048) {
                return;
            }
            into.add(new Jwk("RSA", kid, KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, e))));
        } else if ("EC".equals(kty) && "P-256".equals(string(jwk, "crv"))) {
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            ECParameterSpec spec = parameters.getParameterSpec(ECParameterSpec.class);
            ECPoint point = new ECPoint(big(jwk, "x"), big(jwk, "y"));
            into.add(new Jwk("EC", kid, KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, spec))));
        }
    }

    private static String string(JsonObject jwk, String key) {
        return jwk.get(key) instanceof JsonString s ? s.getString() : null;
    }

    private static BigInteger big(JsonObject jwk, String key) {
        return new BigInteger(1, Base64.getUrlDecoder().decode(jwk.getString(key)));
    }


    record Jwk(String type, String kid, PublicKey key) {
    }
}
