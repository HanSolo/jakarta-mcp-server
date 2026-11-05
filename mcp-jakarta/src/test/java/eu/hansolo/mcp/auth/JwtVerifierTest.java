package eu.hansolo.mcp.auth;

import jakarta.json.Json;
import jakarta.json.JsonObjectBuilder;
import eu.hansolo.mcp.api.Caller;
import org.junit.jupiter.api.Test;

import java.security.PublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtVerifierTest {

    private static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");

    private final AuthConfig  config   = new AuthConfig(true, "https://mcp.example.com/mcp", "https://auth.example.com", "https://auth.example.com/jwks", null, List.of(), List.of(), List.of(), 60, 300, 30, false);
    private final KeyResolver keys     = (kid, alg) -> alg.equals("RS256") ? ("other".equals(kid) ? List.of() : List.<PublicKey>of(TestTokens.RSA.getPublic())) : List.<PublicKey>of(TestTokens.EC.getPublic());
    private final JwtVerifier verifier = new JwtVerifier(config, keys, Clock.fixed(NOW, ZoneOffset.UTC));

    private static JsonObjectBuilder claims() {
        return TestTokens.validClaims();
    }

    private String rsaToken(JsonObjectBuilder claims) {
        return TestTokens.rs256(TestTokens.header("RS256", "k1"), claims, TestTokens.RSA.getPrivate());
    }

    private void assertRejected(String token, String expectedMessage) {
        TokenException e = assertThrows(TokenException.class, () -> verifier.verify(token));
        assertTrue(e.getMessage().contains(expectedMessage), e.getMessage());
    }

    @Test
    void acceptsAValidRs256Token() throws Exception {
        Caller caller = verifier.verify(rsaToken(claims()));
        assertEquals("user-1", caller.subject());
        assertEquals(Set.of("mcp:tools", "files:read"), caller.scopes());
        assertTrue(caller.hasScope("files:read"));
        assertEquals("https://auth.example.com", caller.claims().getString("iss"));
    }

    @Test
    void acceptsAValidEs256Token() throws Exception {
        String token = TestTokens.es256(TestTokens.header("ES256", null), claims(), TestTokens.EC.getPrivate());
        assertEquals("user-1", verifier.verify(token).subject());
    }

    @Test
    void acceptsAudienceArraysAndScpClaims() throws Exception {
        JsonObjectBuilder claims = claims().add("aud", Json.createArrayBuilder().add("https://other").add("https://mcp.example.com/mcp")).remove("scope").add("scp", Json.createArrayBuilder().add("a").add("b"));
        assertEquals(Set.of("a", "b"), verifier.verify(rsaToken(claims)).scopes());
    }

    @Test
    void rejectsTheNoneAlgorithm() {
        assertRejected(TestTokens.unsigned(TestTokens.header("none", null), claims()), "algorithm");
    }

    @Test
    void rejectsHmacTokensSoThePublicKeyCannotBeUsedAsASecret() {
        assertRejected(TestTokens.unsigned(TestTokens.header("HS256", null), claims()), "algorithm");
    }

    @Test
    void rejectsCriticalHeaders() {
        JsonObjectBuilder header = TestTokens.header("RS256", "k1").add("crit", Json.createArrayBuilder().add("exp"));
        assertRejected(TestTokens.rs256(header, claims(), TestTokens.RSA.getPrivate()), "critical");
    }

    @Test
    void rejectsATokenSignedByAnotherKey() {
        String token = TestTokens.rs256(TestTokens.header("RS256", "k1"), claims(), TestTokens.RSA_2.getPrivate());
        assertRejected(token, "signature");
    }

    @Test
    void rejectsATamperedPayload() {
        String token = rsaToken(claims());
        String[] parts = token.split("\\.");
        String forged = TestTokens.b64(claims().add("sub", "admin").build().toString());
        assertRejected(parts[0] + "." + forged + "." + parts[2], "signature");
    }

    @Test
    void rejectsAnUnknownKid() {
        String token = TestTokens.rs256(TestTokens.header("RS256", "other"), claims(), TestTokens.RSA.getPrivate());
        assertRejected(token, "signature");
    }

    @Test
    void rejectsTheWrongIssuer() {
        assertRejected(rsaToken(claims().add("iss", "https://evil.example.com")), "issuer");
    }

    @Test
    void rejectsATokenForAnotherAudienceSoItCannotBePassedThrough() {
        assertRejected(rsaToken(claims().add("aud", "https://other-api.example.com")), "resource");
        assertRejected(rsaToken(claims().remove("aud")), "resource");
    }

    @Test
    void rejectsExpiredTokensBeyondTheClockSkew() throws Exception {
        assertRejected(rsaToken(claims().add("exp", NOW.minusSeconds(61).getEpochSecond())), "expired");
        assertEquals("user-1", verifier.verify(rsaToken(claims().add("exp", NOW.minusSeconds(30).getEpochSecond()))).subject());
    }

    @Test
    void rejectsTokensWithoutExpiry() {
        assertRejected(rsaToken(claims().remove("exp")), "expiry");
    }

    @Test
    void rejectsTokensThatAreNotYetValid() {
        assertRejected(rsaToken(claims().add("nbf", NOW.plusSeconds(120).getEpochSecond())), "not yet valid");
    }

    @Test
    void rejectsMalformedTokens() {
        assertRejected("abc", "Malformed");
        assertRejected("a.b", "Malformed");
        assertRejected("a.b.c.d", "Malformed");
        assertRejected("..", "Malformed");
        assertRejected("!!!.???.###", "Malformed");
        assertRejected("x".repeat(9000), "too large");
    }

    @Test
    void doesNotAcceptAnEs256SignatureOfTheWrongLength() {
        String valid = TestTokens.es256(TestTokens.header("ES256", null), claims(), TestTokens.EC.getPrivate());
        String[] parts = valid.split("\\.");
        assertRejected(parts[0] + "." + parts[1] + "." + TestTokens.b64("short"), "signature");
    }

    @Test
    void joseSignaturesConvertToDerWithMinimalIntegers() {
        byte[] jose = new byte[64];
        jose[31] = 1;
        jose[32] = (byte) 0x80;
        jose[63] = 2;
        byte[] der = JwtVerifier.joseToDer(jose);
        assertEquals(0x30, der[0]);
        assertEquals(0x02, der[2]);
        assertEquals(1, der[3]);
        assertEquals(0x02, der[5]);
        assertEquals(33, der[6]);
        assertEquals(0, der[7]);
    }
}
