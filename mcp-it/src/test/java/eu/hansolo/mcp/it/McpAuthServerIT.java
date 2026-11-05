package eu.hansolo.mcp.it;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The OAuth 2.1 resource-server behaviour against the real WAR: a JDK HttpServer on the host plays the authorization server
 * (publishes /jwks, signs tokens) and the container fetches the keys from it over host.testcontainers.internal.
 */
class McpAuthServerIT {

    private static final String METADATA_URL = "https://mcp.example.com/.well-known/oauth-protected-resource/mcp";

    private static TestIssuer          issuer;
    private static GenericContainer<?> container;
    private static McpClient           client;


    @BeforeAll
    static void start() throws Exception {
        Assumptions.assumeTrue(ServerFixture.dockerAvailable(), "Docker is not available: skipping integration tests");
        issuer = new TestIssuer();
        Testcontainers.exposeHostPorts(issuer.port());
        Map<String, String> environment = Map.of("MCP_AUTH_ENABLED", "true", "MCP_AUTH_RESOURCE", TestIssuer.RESOURCE, "MCP_AUTH_ISSUER", TestIssuer.ISSUER, "MCP_AUTH_JWKS_URI", "http://host.testcontainers.internal:" + issuer.port() + "/jwks", "MCP_AUTH_JWKS_ALLOW_HTTP", "true", "MCP_AUTH_SCOPES_SUPPORTED", "mcp:tools", "MCP_AUTH_REQUIRED_SCOPES", "mcp:tools");
        container = ServerFixture.create(environment, true);
        container.start();
        client = new McpClient(container.getHost(), container.getMappedPort(ServerFixture.httpPort()));
        ServerFixture.awaitDeployed(container, client);
    }

    @AfterAll
    static void stop() {
        if (container != null) {
            container.stop();
        }
        if (issuer != null) {
            issuer.close();
        }
    }

    @Test
    void requestsWithoutCredentialsGet401AndPointToTheMetadata() throws Exception {
        McpClient.Reply reply = client.discover(null);
        assertEquals(401, reply.status());
        assertEquals("Bearer resource_metadata=\"" + METADATA_URL + "\", scope=\"mcp:tools\"", reply.header("WWW-Authenticate"));
    }

    @Test
    void aValidTokenCanListAndCallTools() throws Exception {
        String token = issuer.token("mcp:tools");
        assertEquals(200, client.toolsList(token).status());
        McpClient.Reply call = client.toolsCall("add", Json.createObjectBuilder().add("a", 2).add("b", 3).build(), token);
        assertEquals(200, call.status());
        assertEquals(5, call.json().getJsonObject("result").getJsonObject("structuredContent").getInt("sum"));
    }

    @Test
    void anExpiredTokenIsRejected() throws Exception {
        String token = issuer.token(TestIssuer.ISSUER, TestIssuer.RESOURCE, "mcp:tools", Instant.now().minusSeconds(3600));
        McpClient.Reply reply = client.toolsList(token);
        assertEquals(401, reply.status());
        assertTrue(reply.header("WWW-Authenticate").contains("error=\"invalid_token\""));
    }

    @Test
    void aTokenForAnotherResourceIsRejectedSoNoTokenPassthroughIsPossible() throws Exception {
        String token = issuer.token(TestIssuer.ISSUER, "https://some-other-api.example.com", "mcp:tools", Instant.now().plusSeconds(3600));
        assertEquals(401, client.toolsList(token).status());
    }

    @Test
    void aTokenFromAnotherIssuerIsRejected() throws Exception {
        String token = issuer.token("https://evil.example.com", TestIssuer.RESOURCE, "mcp:tools", Instant.now().plusSeconds(3600));
        assertEquals(401, client.toolsList(token).status());
    }

    @Test
    void aTokenWithoutTheRequiredScopeGets403WithAStepUpChallenge() throws Exception {
        McpClient.Reply reply = client.toolsList(issuer.token("something:else"));
        assertEquals(403, reply.status());
        assertTrue(reply.header("WWW-Authenticate").contains("error=\"insufficient_scope\", scope=\"mcp:tools\""));
    }

    @Test
    void aGarbageTokenIsRejected() throws Exception {
        assertEquals(401, client.toolsList("not.a.jwt").status());
        assertEquals(401, client.toolsList("x").status());
    }

    @Test
    void protectedResourceMetadataIsPublicAndNamesTheAuthorizationServer() throws Exception {
        McpClient.Reply reply = client.get("/.well-known/oauth-protected-resource/mcp", Map.of("Accept", "application/json"));
        assertEquals(200, reply.status());
        JsonObject metadata = reply.json();
        assertEquals(TestIssuer.RESOURCE, metadata.getString("resource"));
        assertEquals(TestIssuer.ISSUER, metadata.getJsonArray("authorization_servers").getString(0));
        assertEquals("mcp:tools", metadata.getJsonArray("scopes_supported").getString(0));
        assertFalse(metadata.containsKey("token"));
    }

    @Test
    void metadataForAnotherPathIs404() throws Exception {
        assertEquals(404, client.get("/.well-known/oauth-protected-resource", Map.of()).status());
        assertEquals(404, client.get("/.well-known/oauth-protected-resource/other", Map.of()).status());
    }
}
