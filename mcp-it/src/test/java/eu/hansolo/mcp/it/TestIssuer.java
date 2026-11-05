package eu.hansolo.mcp.it;

import com.sun.net.httpserver.HttpServer;
import jakarta.json.Json;
import jakarta.json.JsonObjectBuilder;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

/** A throwaway authorization server: signs RS256 access tokens and publishes the key at /jwks on a local port the container can reach. */
final class TestIssuer implements AutoCloseable {

    static final String ISSUER   = "https://auth.example.com";
    static final String RESOURCE = "https://mcp.example.com/mcp";
    static final String KEY_ID   = "it-key-1";

    private final KeyPair    keys;
    private final HttpServer server;


    TestIssuer() throws IOException {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            this.keys = generator.generateKeyPair();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        byte[] document = jwks().getBytes(StandardCharsets.UTF_8);
        this.server = HttpServer.create(new InetSocketAddress(0), 0);
        this.server.createContext("/jwks", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, document.length);
            exchange.getResponseBody().write(document);
            exchange.close();
        });
        this.server.start();
    }


    int port() {
        return server.getAddress().getPort();
    }

    /** A token for this issuer and resource, valid for an hour, carrying the given space-separated scopes. */
    String token(String scopes) {
        return token(ISSUER, RESOURCE, scopes, Instant.now().plusSeconds(3600));
    }

    String token(String issuer, String audience, String scopes, Instant expiry) {
        String header = b64(Json.createObjectBuilder().add("alg", "RS256").add("kid", KEY_ID).add("typ", "at+jwt").build().toString());
        JsonObjectBuilder claims = Json.createObjectBuilder().add("iss", issuer).add("aud", audience).add("sub", "it-user").add("exp", expiry.getEpochSecond()).add("scope", scopes);
        String signingInput = header + "." + b64(claims.build().toString());
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(keys.getPrivate());
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private String jwks() {
        RSAPublicKey key = (RSAPublicKey) keys.getPublic();
        JsonObjectBuilder jwk = Json.createObjectBuilder().add("kty", "RSA").add("use", "sig").add("alg", "RS256").add("kid", KEY_ID).add("n", unsigned(key.getModulus())).add("e", unsigned(key.getPublicExponent()));
        return Json.createObjectBuilder().add("keys", Json.createArrayBuilder().add(jwk)).build().toString();
    }

    private static String unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String b64(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
