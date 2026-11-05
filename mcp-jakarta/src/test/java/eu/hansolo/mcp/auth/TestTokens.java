package eu.hansolo.mcp.auth;

import jakarta.json.Json;
import jakarta.json.JsonObjectBuilder;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

/** Builds signed JWTs for tests. */
final class TestTokens {

    static final KeyPair RSA   = generate("RSA", 2048);
    static final KeyPair RSA_2 = generate("RSA", 2048);
    static final KeyPair EC    = generateEc();


    private TestTokens() {
    }


    static JsonObjectBuilder header(String alg, String kid) {
        JsonObjectBuilder header = Json.createObjectBuilder().add("alg", alg).add("typ", "at+jwt");
        if (kid != null) {
            header.add("kid", kid);
        }
        return header;
    }

    /** A claims object that passes every check for issuer https://auth.example.com, audience https://mcp.example.com/mcp, now = 2026-11-05T12:00:00Z. */
    static JsonObjectBuilder validClaims() {
        return Json.createObjectBuilder().add("iss", "https://auth.example.com").add("aud", "https://mcp.example.com/mcp").add("sub", "user-1").add("exp", 1_900_000_000L).add("scope", "mcp:tools files:read");
    }

    static String rs256(JsonObjectBuilder header, JsonObjectBuilder claims, PrivateKey key) {
        return sign("SHA256withRSA", header, claims, key, false);
    }

    static String es256(JsonObjectBuilder header, JsonObjectBuilder claims, PrivateKey key) {
        return sign("SHA256withECDSA", header, claims, key, true);
    }

    static String unsigned(JsonObjectBuilder header, JsonObjectBuilder claims) {
        return b64(header.build().toString()) + "." + b64(claims.build().toString()) + "." + b64("x");
    }

    private static String sign(String algorithm, JsonObjectBuilder header, JsonObjectBuilder claims, PrivateKey key, boolean jose) {
        String signingInput = b64(header.build().toString()) + "." + b64(claims.build().toString());
        try {
            Signature signature = Signature.getInstance(algorithm);
            signature.initSign(key);
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            byte[] bytes = signature.sign();
            return signingInput + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(jose ? derToJose(bytes) : bytes);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String b64(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    /** DER SEQUENCE of two INTEGERs to 64-byte R||S. */
    private static byte[] derToJose(byte[] der) {
        int offset = 3;
        int rLength = der[offset] & 0xff;
        byte[] r = java.util.Arrays.copyOfRange(der, offset + 1, offset + 1 + rLength);
        offset += 1 + rLength + 1;
        int sLength = der[offset] & 0xff;
        byte[] s = java.util.Arrays.copyOfRange(der, offset + 1, offset + 1 + sLength);
        byte[] out = new byte[64];
        copyRight(r, out, 0);
        copyRight(s, out, 32);
        return out;
    }

    private static void copyRight(byte[] value, byte[] out, int offset) {
        int skip = Math.max(0, value.length - 32);
        int length = value.length - skip;
        System.arraycopy(value, skip, out, offset + 32 - length, length);
    }

    private static KeyPair generate(String algorithm, int bits) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(algorithm);
            generator.initialize(bits);
            return generator.generateKeyPair();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyPair generateEc() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
