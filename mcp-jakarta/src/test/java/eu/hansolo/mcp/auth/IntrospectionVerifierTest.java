package eu.hansolo.mcp.auth;

import com.sun.net.httpserver.HttpServer;
import eu.hansolo.mcp.api.Caller;
import eu.hansolo.mcp.protocol.McpProtocolException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RFC 7662 introspection against a small local server that plays the authorization server. */
class IntrospectionVerifierTest {

    private static final String RESOURCE = "https://mcp.example.com/mcp";
    private static final String ISSUER   = "https://auth.example.com";
    private static final Instant NOW     = Instant.parse("2026-11-06T12:00:00Z");

    private final List<String> bodies         = new CopyOnWriteArrayList<>();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private volatile int       status         = 200;
    private volatile String    answer         = active("alice", "files mcp:tools", RESOURCE, NOW.plusSeconds(3600).getEpochSecond());
    private final MovableClock clock          = new MovableClock(NOW);


    // ---- the verdict -----------------------------------------------------------------------------------------------

    @Test
    void anActiveTokenYieldsTheCallerWithSubjectScopesAndClaims() throws Exception {
        try (Server server = new Server()) {
            Caller caller = verifier(server, "", "", 30, true).verify("opaque-token");
            assertEquals("alice", caller.subject());
            assertEquals(Set.of("files", "mcp:tools"), caller.scopes());
            assertEquals("alice", caller.claims().getString("sub"));
        }
    }

    @Test
    void theTokenIsSentAsAFormParameterAndNothingElseLeaks() throws Exception {
        try (Server server = new Server()) {
            verifier(server, "", "", 30, true).verify("a+b/c=d");
            assertEquals("token=a%2Bb%2Fc%3Dd&token_type_hint=access_token", bodies.get(0));
            assertTrue(authorizations.get(0).isEmpty(), "no credentials configured, none sent");
        }
    }

    @Test
    void clientCredentialsGoAsHttpBasicWithFormEncodedParts() throws Exception {
        try (Server server = new Server()) {
            verifier(server, "my client", "se:cret", 30, true).verify("t");
            String expected = Base64.getEncoder().encodeToString("my+client:se%3Acret".getBytes(StandardCharsets.UTF_8));
            assertEquals("Basic " + expected, authorizations.get(0));
        }
    }

    @Test
    void anInactiveTokenIsRefused() throws Exception {
        try (Server server = new Server()) {
            answer = "{\"active\":false}";
            assertEquals("Token is not active", assertThrows(TokenException.class, () -> verifier(server, "", "", 30, true).verify("t")).getMessage());
            answer = "{\"active\":\"true\",\"aud\":\"" + RESOURCE + "\"}";
            assertThrows(TokenException.class, () -> verifier(server, "", "", 30, true).verify("t"));
            answer = "{\"sub\":\"alice\",\"aud\":\"" + RESOURCE + "\"}";
            assertThrows(TokenException.class, () -> verifier(server, "", "", 30, true).verify("t"));
        }
    }

    @Test
    void aTokenForAnotherAudienceOrIssuerIsRefused() throws Exception {
        try (Server server = new Server()) {
            answer = active("alice", "files", "https://other.example.com/mcp", NOW.plusSeconds(60).getEpochSecond());
            assertEquals("Token was not issued for this resource", assertThrows(TokenException.class, () -> verifier(server, "", "", 30, true).verify("t")).getMessage());
            answer = "{\"active\":true,\"iss\":\"https://evil.example.com\",\"aud\":\"" + RESOURCE + "\"}";
            assertEquals("Wrong issuer", assertThrows(TokenException.class, () -> verifier(server, "", "", 30, true).verify("t")).getMessage());
            answer = "{\"active\":true,\"iss\":\"" + ISSUER + "\",\"aud\":[\"x\",\"" + RESOURCE + "\"],\"sub\":\"bob\"}";
            assertEquals("bob", verifier(server, "", "", 30, true).verify("t").subject());
        }
    }

    @Test
    void anAnswerWithoutAudienceNeedsTheSettingToBeAccepted() throws Exception {
        try (Server server = new Server()) {
            answer = "{\"active\":true,\"sub\":\"alice\",\"scope\":\"files\"}";
            assertThrows(TokenException.class, () -> verifier(server, "", "", 30, true).verify("t"));
            assertEquals("alice", verifier(server, "", "", 30, false).verify("t").subject());
            answer = "{\"active\":true,\"sub\":\"alice\",\"aud\":\"https://other.example.com/mcp\"}";
            assertThrows(TokenException.class, () -> verifier(server, "", "", 30, false).verify("t"));   // a wrong audience is never accepted
        }
    }

    @Test
    void expiryAndNotBeforeAreChecked() throws Exception {
        try (Server server = new Server()) {
            answer = active("alice", "files", RESOURCE, NOW.minusSeconds(120).getEpochSecond());
            assertEquals("Token expired", assertThrows(TokenException.class, () -> verifier(server, "", "", 30, true).verify("t")).getMessage());
            answer = active("alice", "files", RESOURCE, NOW.minusSeconds(30).getEpochSecond());
            assertEquals("alice", verifier(server, "", "", 30, true).verify("t").subject(), "inside the clock skew");
            answer = "{\"active\":true,\"aud\":\"" + RESOURCE + "\",\"nbf\":" + NOW.plusSeconds(600).getEpochSecond() + "}";
            assertEquals("Token not yet valid", assertThrows(TokenException.class, () -> verifier(server, "", "", 30, true).verify("t")).getMessage());
        }
    }

    @Test
    void anOversizedTokenNeverReachesTheServer() throws Exception {
        try (Server server = new Server()) {
            assertThrows(TokenException.class, () -> verifier(server, "", "", 30, true).verify("x".repeat(9000)));
            assertTrue(bodies.isEmpty());
        }
    }

    // ---- caching ---------------------------------------------------------------------------------------------------

    @Test
    void answersAreReusedUntilTheCacheTimeEnds() throws Exception {
        try (Server server = new Server()) {
            IntrospectionVerifier verifier = verifier(server, "", "", 30, true);
            verifier.verify("t");
            verifier.verify("t");
            verifier.verify("other");
            assertEquals(2, bodies.size());
            clock.advance(31);
            verifier.verify("t");
            assertEquals(3, bodies.size());
        }
    }

    @Test
    void noCachingMeansEveryRequestAsks() throws Exception {
        try (Server server = new Server()) {
            IntrospectionVerifier verifier = verifier(server, "", "", 0, true);
            verifier.verify("t");
            verifier.verify("t");
            assertEquals(2, bodies.size());
        }
    }

    @Test
    void anAnswerIsNeverReusedPastTheTokensOwnExpiry() throws Exception {
        try (Server server = new Server()) {
            answer = active("alice", "files", RESOURCE, NOW.plusSeconds(10).getEpochSecond());
            IntrospectionVerifier verifier = verifier(server, "", "", 300, true);
            verifier.verify("t");
            clock.advance(11);
            verifier.verify("t");   // asks again; the server (still) says active, the expiry check has the skew
            assertEquals(2, bodies.size());
        }
    }

    @Test
    void aRefusalIsRememberedBriefly() throws Exception {
        try (Server server = new Server()) {
            answer = "{\"active\":false}";
            IntrospectionVerifier verifier = verifier(server, "", "", 300, true);
            assertThrows(TokenException.class, () -> verifier.verify("t"));
            assertThrows(TokenException.class, () -> verifier.verify("t"));
            assertEquals(1, bodies.size());
            clock.advance(6);
            assertThrows(TokenException.class, () -> verifier.verify("t"));
            assertEquals(2, bodies.size(), "a refusal is cached for at most five seconds");
        }
    }

    // ---- when the endpoint fails -----------------------------------------------------------------------------------

    @Test
    void aFailingEndpointIsAnHttp503AndNeverCached() throws Exception {
        try (Server server = new Server()) {
            IntrospectionVerifier verifier = verifier(server, "", "", 30, true);
            for (int code : new int[] {500, 401, 403, 404}) {
                status = code;
                McpProtocolException e = assertThrows(McpProtocolException.class, () -> verifier.verify("t"));
                assertEquals(503, e.httpStatus());
            }
            status = 200;
            assertEquals("alice", verifier.verify("t").subject());
        }
    }

    @Test
    void garbageFromTheEndpointIsAnHttp503() throws Exception {
        try (Server server = new Server()) {
            answer = "not json";
            assertEquals(503, assertThrows(McpProtocolException.class, () -> verifier(server, "", "", 30, true).verify("t")).httpStatus());
            answer = "[1,2]";
            assertEquals(503, assertThrows(McpProtocolException.class, () -> verifier(server, "", "", 30, true).verify("t")).httpStatus());
        }
    }

    @Test
    void anEndpointThatIsNotThereIsAnHttp503() throws Exception {
        int port;
        try (Server server = new Server()) {
            port = server.port();
        }
        AuthConfig config = config("http://127.0.0.1:" + port + "/introspect", "", "", 30, true);
        assertEquals(503, assertThrows(McpProtocolException.class, () -> new IntrospectionVerifier(config, clock).verify("t")).httpStatus());
    }

    // ---- wiring ----------------------------------------------------------------------------------------------------

    @Test
    void theBearerAuthenticatorUsesIntrospectionWhenConfigured() throws Exception {
        try (Server server = new Server()) {
            answer = active("alice", "files", RESOURCE, Instant.now().plusSeconds(3600).getEpochSecond());   // the authenticator uses the system clock
            AuthConfig config = config(server.url(), "", "", 30, true);
            BearerJwtAuthenticator authenticator = new BearerJwtAuthenticator(config, (kid, alg) -> List.of());
            assertEquals("alice", authenticator.authenticate("Bearer opaque-token").subject());
            assertThrows(TokenException.class, () -> authenticator.authenticate(null));
            assertThrows(TokenException.class, () -> authenticator.authenticate("Basic abc"));
        }
    }

    @Test
    void configurationRulesAreEnforcedAtStartup() {
        assertThrows(IllegalStateException.class, () -> new AuthConfig(true, RESOURCE, ISSUER, "https://auth.example.com/jwks", null, List.of(), List.of(), List.of(), 60, 300, 30, false, "https://auth.example.com/introspect", null, null, 30, true, false));
        assertThrows(IllegalStateException.class, () -> new AuthConfig(true, RESOURCE, ISSUER, null, null, List.of(), List.of(), List.of(), 60, 300, 30, false, "http://auth.example.com/introspect", null, null, 30, true, false));
        assertThrows(IllegalStateException.class, () -> new AuthConfig(true, RESOURCE, ISSUER, null, null, List.of(), List.of(), List.of(), 60, 300, 30, false, "https://auth.example.com/introspect", null, "secret", 30, true, false));
        assertThrows(IllegalStateException.class, () -> new AuthConfig(true, RESOURCE, ISSUER, null, null, List.of(), List.of(), List.of(), 60, 300, 30, false, null, null, null, 30, true, false));
        AuthConfig ok = new AuthConfig(true, RESOURCE, ISSUER, null, null, List.of(), List.of(), List.of(), 60, 300, 30, false, "https://auth.example.com/introspect", "id", "secret", 15, false, false);
        assertEquals("https://auth.example.com/introspect", ok.introspectionUri().toString());
        assertEquals(15, ok.introspectionCache().toSeconds());
        assertFalse(ok.introspectionRequireAudience());
        assertEquals(null, ok.jwksUri());
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private IntrospectionVerifier verifier(Server server, String clientId, String clientSecret, long cacheSeconds, boolean requireAudience) {
        return new IntrospectionVerifier(config(server.url(), clientId, clientSecret, cacheSeconds, requireAudience), clock);
    }

    private static AuthConfig config(String uri, String clientId, String clientSecret, long cacheSeconds, boolean requireAudience) {
        return new AuthConfig(true, RESOURCE, ISSUER, null, null, List.of(), List.of(), List.of(), 60, 300, 30, false, uri, clientId.isEmpty() ? null : clientId, clientSecret.isEmpty() ? null : clientSecret, cacheSeconds, requireAudience, true);
    }

    private static String active(String subject, String scope, String audience, long exp) {
        return "{\"active\":true,\"sub\":\"" + subject + "\",\"scope\":\"" + scope + "\",\"aud\":\"" + audience + "\",\"iss\":\"" + ISSUER + "\",\"exp\":" + exp + "}";
    }


    /** An introspection endpoint on a free loopback port. */
    private final class Server implements AutoCloseable {

        private final HttpServer server;


        Server() throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/introspect", exchange -> {
                bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                authorizations.add(exchange.getRequestHeaders().getFirst("Authorization") == null ? "" : exchange.getRequestHeaders().getFirst("Authorization"));
                byte[] reply = answer.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, status == 200 ? reply.length : -1);
                if (status == 200) {
                    exchange.getResponseBody().write(reply);
                }
                exchange.close();
            });
            server.start();
        }


        int port() {
            return server.getAddress().getPort();
        }

        String url() {
            return "http://127.0.0.1:" + port() + "/introspect";
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static final class MovableClock extends Clock {

        private Instant now;


        MovableClock(Instant start) {
            this.now = start;
        }


        void advance(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
