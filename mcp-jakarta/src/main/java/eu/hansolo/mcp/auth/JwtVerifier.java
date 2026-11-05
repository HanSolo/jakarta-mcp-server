package eu.hansolo.mcp.auth;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonException;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import eu.hansolo.mcp.api.Caller;

import java.io.ByteArrayInputStream;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates a compact-serialized JWT access token: algorithm allow-list (RS256, ES256 only; never "none" or HMAC), signature
 * against keys from the configured issuer, then {@code iss}, {@code aud} (RFC 8707 resource binding), {@code exp} and {@code nbf}.
 * Header fields that name keys ({@code jku}, {@code jwk}, {@code x5u}) are ignored, so a token can never make the server fetch anything.
 */
public final class JwtVerifier {

    private static final int MAX_TOKEN_CHARS = 8192;

    private final AuthConfig  config;
    private final KeyResolver keys;
    private final Clock       clock;


    public JwtVerifier(AuthConfig config, KeyResolver keys, Clock clock) {
        this.config = config;
        this.keys   = keys;
        this.clock  = clock;
    }


    public Caller verify(String token) throws TokenException {
        if (token.length() > MAX_TOKEN_CHARS) {
            throw new TokenException("Token too large");
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
            throw new TokenException("Malformed token");
        }
        JsonObject header = json(parts[0]);
        JsonObject claims = json(parts[1]);
        byte[] signature  = decode(parts[2]);
        if (header.containsKey("crit")) {
            throw new TokenException("Unsupported critical header");
        }
        String alg = header.get("alg") instanceof JsonString a ? a.getString() : "";
        if (!alg.equals("RS256") && !alg.equals("ES256")) {
            throw new TokenException("Unsupported signing algorithm");
        }
        String kid = header.get("kid") instanceof JsonString k ? k.getString() : null;
        byte[] signed = (parts[0] + "." + parts[1]).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        if (!signatureValid(alg, kid, signed, signature)) {
            throw new TokenException("Invalid signature");
        }
        checkClaims(claims);
        return new Caller(claims.get("sub") instanceof JsonString s ? s.getString() : "", scopesOf(claims), claims);
    }

    private boolean signatureValid(String alg, String kid, byte[] signed, byte[] signature) {
        List<PublicKey> candidates = keys.candidates(kid, alg);
        for (PublicKey key : candidates) {
            try {
                Signature verifier = Signature.getInstance(alg.equals("RS256") ? "SHA256withRSA" : "SHA256withECDSA");
                verifier.initVerify(key);
                verifier.update(signed);
                byte[] der = alg.equals("ES256") ? joseToDer(signature) : signature;
                if (der != null && verifier.verify(der)) {
                    return true;
                }
            } catch (GeneralSecurityException | RuntimeException e) {
                // wrong key type for this algorithm or a malformed signature: try the next candidate
            }
        }
        return false;
    }

    private void checkClaims(JsonObject claims) throws TokenException {
        if (!(claims.get("iss") instanceof JsonString iss) || !iss.getString().equals(config.issuer())) {
            throw new TokenException("Wrong issuer");
        }
        if (!audienceMatches(claims.get("aud"))) {
            throw new TokenException("Token was not issued for this resource");
        }
        Instant now = clock.instant();
        if (!(claims.get("exp") instanceof JsonNumber exp)) {
            throw new TokenException("Token has no expiry");
        }
        if (!now.minus(config.clockSkew()).isBefore(Instant.ofEpochSecond(exp.longValue()))) {
            throw new TokenException("Token expired");
        }
        if (claims.get("nbf") instanceof JsonNumber nbf && now.plus(config.clockSkew()).isBefore(Instant.ofEpochSecond(nbf.longValue()))) {
            throw new TokenException("Token not yet valid");
        }
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

    /** Scopes from {@code scope} (space-separated string, RFC 9068) or {@code scp} (array or string, as some issuers emit). */
    private static Set<String> scopesOf(JsonObject claims) {
        Set<String> scopes = new LinkedHashSet<>();
        for (String key : new String[] {"scope", "scp"}) {
            JsonValue value = claims.get(key);
            if (value instanceof JsonString s) {
                Arrays.stream(s.getString().split(" ")).filter(x -> !x.isEmpty()).forEach(scopes::add);
            } else if (value instanceof JsonArray array) {
                array.stream().filter(v -> v instanceof JsonString).map(v -> ((JsonString) v).getString()).forEach(scopes::add);
            }
        }
        return scopes;
    }

    private static JsonObject json(String segment) throws TokenException {
        try (var reader = Json.createReader(new ByteArrayInputStream(decode(segment)))) {
            return reader.readObject();
        } catch (JsonException | IllegalStateException | ClassCastException e) {
            throw new TokenException("Malformed token");
        }
    }

    private static byte[] decode(String segment) throws TokenException {
        try {
            return Base64.getUrlDecoder().decode(segment);
        } catch (IllegalArgumentException e) {
            throw new TokenException("Malformed token");
        }
    }

    /** JWS ECDSA signatures are the raw 64-byte R||S; the JCA wants ASN.1 DER. Returns null for a wrong length. */
    static byte[] joseToDer(byte[] jose) {
        if (jose.length != 64) {
            return null;
        }
        byte[] r = integer(jose, 0);
        byte[] s = integer(jose, 32);
        byte[] der = new byte[6 + r.length + s.length];
        der[0] = 0x30;
        der[1] = (byte) (4 + r.length + s.length);
        der[2] = 0x02;
        der[3] = (byte) r.length;
        System.arraycopy(r, 0, der, 4, r.length);
        der[4 + r.length] = 0x02;
        der[5 + r.length] = (byte) s.length;
        System.arraycopy(s, 0, der, 6 + r.length, s.length);
        return der;
    }

    /** 32 unsigned big-endian bytes to a minimal two's-complement DER INTEGER body. */
    private static byte[] integer(byte[] bytes, int offset) {
        int start = offset;
        while (start < offset + 31 && bytes[start] == 0) {
            start++;
        }
        int length = offset + 32 - start;
        boolean pad = (bytes[start] & 0x80) != 0;
        byte[] out = new byte[length + (pad ? 1 : 0)];
        System.arraycopy(bytes, start, out, pad ? 1 : 0, length);
        return out;
    }
}
