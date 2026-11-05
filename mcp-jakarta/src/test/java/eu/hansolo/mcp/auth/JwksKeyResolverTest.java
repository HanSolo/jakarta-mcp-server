package eu.hansolo.mcp.auth;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwksKeyResolverTest {

    private static String b64(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            bytes = java.util.Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static JsonObjectBuilder rsaJwk(RSAPublicKey key, String kid) {
        return Json.createObjectBuilder().add("kty", "RSA").add("kid", kid).add("use", "sig").add("n", b64(key.getModulus())).add("e", b64(key.getPublicExponent()));
    }

    private static JsonObjectBuilder ecJwk(ECPublicKey key, String kid) {
        return Json.createObjectBuilder().add("kty", "EC").add("crv", "P-256").add("kid", kid).add("x", b64(key.getW().getAffineX())).add("y", b64(key.getW().getAffineY()));
    }

    private static String jwks(JsonObjectBuilder... keys) {
        JsonArrayBuilder array = Json.createArrayBuilder();
        for (JsonObjectBuilder key : keys) {
            array.add(key);
        }
        return Json.createObjectBuilder().add("keys", array).build().toString();
    }

    @Test
    void parsesRsaAndEcKeys() {
        String document = jwks(rsaJwk((RSAPublicKey) TestTokens.RSA.getPublic(), "r1"), ecJwk((ECPublicKey) TestTokens.EC.getPublic(), "e1"));
        List<JwksKeyResolver.Jwk> keys = JwksKeyResolver.parse(document);
        assertEquals(2, keys.size());
        assertEquals("RSA", keys.get(0).type());
        assertEquals("r1", keys.get(0).kid());
        assertEquals(TestTokens.RSA.getPublic(), keys.get(0).key());
        assertEquals(TestTokens.EC.getPublic(), keys.get(1).key());
    }

    @Test
    void skipsEncryptionKeysUnsupportedTypesAndWeakRsaKeys() {
        JsonObjectBuilder enc = rsaJwk((RSAPublicKey) TestTokens.RSA.getPublic(), "enc").add("use", "enc");
        JsonObjectBuilder oct = Json.createObjectBuilder().add("kty", "oct").add("k", "AAAA");
        JsonObjectBuilder p384 = ecJwk((ECPublicKey) TestTokens.EC.getPublic(), "p384").add("crv", "P-384");
        JsonObjectBuilder weak = Json.createObjectBuilder().add("kty", "RSA").add("kid", "weak").add("n", "AQAB").add("e", "AQAB");
        assertTrue(JwksKeyResolver.parse(jwks(enc, oct, p384, weak)).isEmpty());
    }

    @Test
    void garbageDocumentsYieldNoKeysInsteadOfThrowing() {
        assertTrue(JwksKeyResolver.parse("{\"nokeys\":true}").isEmpty());
        assertTrue(JwksKeyResolver.parse("{\"keys\":[1,\"x\",{}]}").isEmpty());
    }
}
