package eu.hansolo.mcp.it;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.io.StringReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The resource-server behaviour against a real identity provider: Keycloak, with the realm from {@code keycloak/mcp-realm.json},
 * the same file the Docker Compose setup uses.
 *
 * <p>The issuer is the tricky part. Tokens carry the issuer Keycloak was told to use ({@code KC_HOSTNAME}), the test fetches them
 * from the host, and the server container fetches keys through the Docker network. So Keycloak is pinned to a fixed host port,
 * its issuer is {@code http://localhost:<port>/realms/mcp}, and the server reads keys from {@code http://keycloak:8080/...}.
 */
class McpKeycloakIT {

    private static final String RESOURCE = "http://localhost:8080/mcp";
    private static final String SECRET   = "mcp-test-secret";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private static Network             network;
    private static GenericContainer<?> keycloak;
    private static GenericContainer<?> server;
    private static McpClient           client;
    private static String              issuer;


    @BeforeAll
    static void start() throws Exception {
        Assumptions.assumeTrue(ServerFixture.dockerAvailable(), "Docker is not available: skipping integration tests");
        Path realm = Path.of(System.getProperty("mcp.realm", "../keycloak/mcp-realm.json")).toAbsolutePath().normalize();
        if (!Files.isRegularFile(realm)) {
            throw new IllegalStateException("Realm file not found at " + realm);
        }
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        issuer  = "http://localhost:" + port + "/realms/mcp";
        network = Network.newNetwork();

        keycloak = new GenericContainer<>(DockerImageName.parse(System.getProperty("keycloak.image", "").isBlank() ? "quay.io/keycloak/keycloak:26.4" : System.getProperty("keycloak.image").trim()))
                .withNetwork(network).withNetworkAliases("keycloak").withExposedPorts(8080)
                .withEnv(Map.of("KC_BOOTSTRAP_ADMIN_USERNAME", "admin", "KC_BOOTSTRAP_ADMIN_PASSWORD", "admin", "KC_HOSTNAME", "http://localhost:" + port))
                .withCommand("start-dev", "--import-realm")
                .withCopyFileToContainer(MountableFile.forHostPath(realm), "/opt/keycloak/data/import/mcp-realm.json")
                .waitingFor(Wait.forHttp("/realms/mcp").forPort(8080).forStatusCode(200))
                .withStartupTimeout(Duration.ofMinutes(5));
        keycloak.setPortBindings(List.of(port + ":8080"));
        keycloak.start();

        Map<String, String> environment = Map.of("MCP_AUTH_ENABLED", "true", "MCP_AUTH_RESOURCE", RESOURCE, "MCP_AUTH_ISSUER", issuer, "MCP_AUTH_JWKS_URI", "http://keycloak:8080/realms/mcp/protocol/openid-connect/certs", "MCP_AUTH_JWKS_ALLOW_HTTP", "true", "MCP_AUTH_AUTHORIZATION_SERVERS", issuer, "MCP_AUTH_SCOPES_SUPPORTED", "mcp:tools", "MCP_AUTH_REQUIRED_SCOPES", "mcp:tools");
        server = ServerFixture.create(environment, false);
        server.withNetwork(network);
        server.start();
        client = new McpClient(server.getHost(), server.getMappedPort(ServerFixture.httpPort()));
        ServerFixture.awaitDeployed(server, client);
    }

    @AfterAll
    static void stop() {
        if (server != null) {
            server.stop();
        }
        if (keycloak != null) {
            keycloak.stop();
        }
        if (network != null) {
            network.close();
        }
    }

    @Test
    void keycloakAnnouncesTheIssuerTheServerIsConfiguredWith() throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(issuer + "/.well-known/openid-configuration")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(issuer, json(response.body()).getString("issuer"));
    }

    @Test
    void requestsWithoutATokenGet401WithTheMetadataPointer() throws Exception {
        McpClient.Reply reply = client.discover(null);
        assertEquals(401, reply.status());
        assertTrue(reply.header("WWW-Authenticate").contains("resource_metadata=\"http://localhost:8080/.well-known/oauth-protected-resource/mcp\""), reply.header("WWW-Authenticate"));
    }

    @Test
    void theMetadataDocumentNamesKeycloakAsAuthorizationServer() throws Exception {
        McpClient.Reply reply = client.get("/.well-known/oauth-protected-resource/mcp", Map.of());
        assertEquals(200, reply.status());
        JsonObject metadata = reply.json();
        assertEquals(RESOURCE, metadata.getString("resource"));
        assertEquals(issuer, metadata.getJsonArray("authorization_servers").getString(0));
        assertEquals("mcp:tools", metadata.getJsonArray("scopes_supported").getString(0));
    }

    @Test
    void aKeycloakTokenWithTheRightAudienceAndScopeCanUseTheServer() throws Exception {
        String token = token("mcp-test", "mcp:tools");
        assertEquals(200, client.toolsList(token).status());
        McpClient.Reply call = client.toolsCall("add", Json.createObjectBuilder().add("a", 20).add("b", 22).build(), token);
        assertEquals(200, call.status());
        assertEquals(42, call.json().getJsonObject("result").getJsonObject("structuredContent").getInt("sum"));
        assertEquals(200, client.rpc("resources/list", 1, Json.createObjectBuilder(), null, token, Json.createObjectBuilder().build()).status());
    }

    @Test
    void aTokenWithoutTheRequiredScopeGets403WithAStepUpChallenge() throws Exception {
        String token = token("mcp-test", "files:read");
        McpClient.Reply reply = client.toolsList(token);
        assertEquals(403, reply.status());
        assertTrue(reply.header("WWW-Authenticate").contains("error=\"insufficient_scope\""), reply.header("WWW-Authenticate"));
        assertTrue(reply.header("WWW-Authenticate").contains("scope=\"mcp:tools\""), reply.header("WWW-Authenticate"));
    }

    @Test
    void aTokenNotBoundToThisServerIsRejected() throws Exception {
        String token = token("mcp-no-audience", "mcp:tools");
        McpClient.Reply reply = client.toolsList(token);
        assertEquals(401, reply.status());
        assertTrue(reply.header("WWW-Authenticate").contains("error=\"invalid_token\""), reply.header("WWW-Authenticate"));
    }

    @Test
    void aTamperedSignatureIsRejected() throws Exception {
        String token = token("mcp-test", "mcp:tools");
        int i = token.length() - 10;
        String tampered = token.substring(0, i) + (token.charAt(i) == 'A' ? 'B' : 'A') + token.substring(i + 1);
        assertEquals(401, client.toolsList(tampered).status());
    }

    /** Client-credentials grant against the host-visible issuer, which is the issuer the tokens will carry. */
    private static String token(String clientId, String scope) throws IOException, InterruptedException {
        String form = "grant_type=client_credentials&client_id=" + enc(clientId) + "&client_secret=" + enc(SECRET) + "&scope=" + enc(scope);
        HttpRequest request = HttpRequest.newBuilder(URI.create(issuer + "/protocol/openid-connect/token")).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "Token request for " + clientId + " failed: " + response.body());
        return json(response.body()).getString("access_token");
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static JsonObject json(String body) {
        return Json.createReader(new StringReader(body)).readObject();
    }
}
