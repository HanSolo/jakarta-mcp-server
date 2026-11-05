package eu.hansolo.mcp.auth;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthConfigTest {

    private static AuthConfig config(String resource, String issuer, String jwks, boolean allowHttp) {
        return new AuthConfig(true, resource, issuer, jwks, null, List.of(), List.of("mcp:tools"), List.of(), 60, 300, 30, allowHttp);
    }

    @Test
    void disabledNeedsNothing() {
        AuthConfig disabled = AuthConfig.disabled();
        assertFalse(disabled.enabled());
    }

    @Test
    void enabledDerivesAudienceServersAndMetadataUrl() {
        AuthConfig config = config("https://MCP.example.com/mcp", "https://auth.example.com", "https://auth.example.com/jwks", false);
        assertEquals("https://mcp.example.com/mcp", config.resource());
        assertEquals("https://mcp.example.com/mcp", config.audience());
        assertEquals(List.of("https://auth.example.com"), config.authorizationServers());
        assertEquals("https://mcp.example.com/.well-known/oauth-protected-resource/mcp", config.metadataUrl());
        assertEquals("/mcp", config.resourcePath());
    }

    @Test
    void rootResourceHasNoPathSuffix() {
        AuthConfig config = config("https://mcp.example.com/", "https://auth.example.com", "https://auth.example.com/jwks", false);
        assertEquals("https://mcp.example.com", config.resource());
        assertEquals("https://mcp.example.com/.well-known/oauth-protected-resource", config.metadataUrl());
    }

    @Test
    void enabledWithoutResourceIssuerOrJwksFailsFast() {
        assertThrows(IllegalStateException.class, () -> config(null, "https://a", "https://a/jwks", false));
        assertThrows(IllegalStateException.class, () -> config("https://m/mcp", null, "https://a/jwks", false));
        assertThrows(IllegalStateException.class, () -> config("https://m/mcp", "https://a", null, false));
    }

    @Test
    void jwksMustBeHttpsUnlessExplicitlyRelaxed() {
        assertThrows(IllegalStateException.class, () -> config("https://m/mcp", "https://a", "http://a/jwks", false));
        assertTrue(config("https://m/mcp", "https://a", "http://a/jwks", true).enabled());
    }

    @Test
    void malformedUrisAreRejected() {
        assertThrows(IllegalStateException.class, () -> config("not a uri", "https://a", "https://a/jwks", false));
        assertThrows(IllegalStateException.class, () -> config("ftp://m/mcp", "https://a", "https://a/jwks", false));
        assertThrows(IllegalStateException.class, () -> config("https://m/mcp#frag", "https://a", "https://a/jwks", false));
    }
}
