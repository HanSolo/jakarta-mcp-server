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
 * Token introspection (RFC 7662) against Keycloak instead of local JWT verification: the server asks Keycloak whether each token is
 * active and bound to it. Same realm and the same issuer trick as {@link McpKeycloakIT}. The server authenticates at the
 * introspection endpoint as the confidential client {@code mcp-test}.
 */
class IntrospectionIT {

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

        Map<String, String> environment = Map.of(
                "MCP_AUTH_ENABLED", "true",
                "MCP_AUTH_RESOURCE", RESOURCE,
                "MCP_AUTH_ISSUER", issuer,
                "MCP_AUTH_INTROSPECTION_URI", "http://keycloak:8080/realms/mcp/protocol/openid-connect/token/introspect",
                "MCP_AUTH_INTROSPECTION_ALLOW_HTTP", "true",
                "MCP_AUTH_INTROSPECTION_CLIENT_ID", "mcp-test",
                "MCP_AUTH_INTROSPECTION_CLIENT_SECRET", SECRET,
                "MCP_AUTH_INTROSPECTION_CACHE_SECONDS", "0",
                "MCP_AUTH_AUTHORIZATION_SERVERS", issuer,
                "MCP_AUTH_REQUIRED_SCOPES", "mcp:tools");
        server = ServerFixture.create(ServerFixture.fixtureWar(), environment, false);
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
    void requestsWithoutATokenGet401() throws Exception {
        McpClient.Reply reply = client.discover(null);
        assertEquals(401, reply.status());
        assertTrue(reply.header("WWW-Authenticate").contains("resource_metadata="), reply.header("WWW-Authenticate"));
    }

    @Test
    void aTokenKeycloakCallsActiveAndBoundToTheServerIsAccepted() throws Exception {
        String token = token("mcp-test", "mcp:tools");
        assertEquals(200, client.toolsList(token).status());
        McpClient.Reply call = client.toolsCall("shape_area", Json.createObjectBuilder().add("shape", Json.createObjectBuilder().add("kind", "rect").add("width", 2).add("height", 5)).build(), token);
        assertEquals(200, call.status(), call.body());
        assertEquals(10.0, call.json().getJsonObject("result").getJsonObject("structuredContent").getJsonNumber("area").doubleValue(), 0.0001);
    }

    @Test
    void aTokenThatIsNotATokenIsRejected() throws Exception {
        McpClient.Reply reply = client.toolsList("this-is-not-a-token");
        assertEquals(401, reply.status());
        assertTrue(reply.header("WWW-Authenticate").contains("error=\"invalid_token\""), reply.header("WWW-Authenticate"));
    }

    @Test
    void aTamperedTokenIsRejected() throws Exception {
        String token = token("mcp-test", "mcp:tools");
        int i = token.length() - 10;
        String tampered = token.substring(0, i) + (token.charAt(i) == 'A' ? 'B' : 'A') + token.substring(i + 1);
        assertEquals(401, client.toolsList(tampered).status());
    }

    @Test
    void aTokenNotBoundToThisServerIsRejected() throws Exception {
        String token = token("mcp-no-audience", "mcp:tools");
        McpClient.Reply reply = client.toolsList(token);
        assertEquals(401, reply.status());
        assertTrue(reply.header("WWW-Authenticate").contains("error=\"invalid_token\""), reply.header("WWW-Authenticate"));
    }

    @Test
    void aTokenWithoutTheRequiredScopeGets403() throws Exception {
        McpClient.Reply reply = client.toolsList(token("mcp-test", "files:read"));
        assertEquals(403, reply.status());
        assertTrue(reply.header("WWW-Authenticate").contains("insufficient_scope"), reply.header("WWW-Authenticate"));
    }

    private static String token(String clientId, String scope) throws IOException, InterruptedException {
        String form = "grant_type=client_credentials&client_id=" + enc(clientId) + "&client_secret=" + enc(SECRET) + "&scope=" + enc(scope);
        HttpRequest request = HttpRequest.newBuilder(URI.create(issuer + "/protocol/openid-connect/token")).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "Token request for " + clientId + " failed: " + response.body());
        JsonObject json = Json.createReader(new StringReader(response.body())).readObject();
        return json.getString("access_token");
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
