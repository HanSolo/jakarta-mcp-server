package eu.hansolo.mcp.it;

import jakarta.json.Json;
import jakarta.json.JsonObject;
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

/**
 * A Keycloak with the realm from {@code keycloak/mcp-realm.json} on a Docker network, pinned to a fixed host port so that the
 * issuer in the tokens ({@code http://localhost:<port>/realms/mcp}) is the same for the test and the server container, which
 * reads keys through the network alias {@code keycloak}. See {@link McpKeycloakIT} for the long version of why.
 */
final class KeycloakSupport {

    static final String RESOURCE = "http://localhost:8080/mcp";

    private static final String     SECRET = "mcp-test-secret";
    private static final HttpClient HTTP   = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    final Network             network = Network.newNetwork();
    final String              issuer;
    private final GenericContainer<?> keycloak;


    KeycloakSupport() throws IOException {
        Path realm = Path.of(System.getProperty("mcp.realm", "../keycloak/mcp-realm.json")).toAbsolutePath().normalize();
        if (!Files.isRegularFile(realm)) {
            throw new IllegalStateException("Realm file not found at " + realm);
        }
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        issuer   = "http://localhost:" + port + "/realms/mcp";
        keycloak = new GenericContainer<>(DockerImageName.parse(System.getProperty("keycloak.image", "").isBlank() ? "quay.io/keycloak/keycloak:26.4" : System.getProperty("keycloak.image").trim()))
                .withNetwork(network).withNetworkAliases("keycloak").withExposedPorts(8080)
                .withEnv(Map.of("KC_BOOTSTRAP_ADMIN_USERNAME", "admin", "KC_BOOTSTRAP_ADMIN_PASSWORD", "admin", "KC_HOSTNAME", "http://localhost:" + port))
                .withCommand("start-dev", "--import-realm")
                .withCopyFileToContainer(MountableFile.forHostPath(realm), "/opt/keycloak/data/import/mcp-realm.json")
                .waitingFor(Wait.forHttp("/realms/mcp").forPort(8080).forStatusCode(200))
                .withStartupTimeout(Duration.ofMinutes(5));
        keycloak.setPortBindings(List.of(port + ":8080"));
    }


    void start() {
        keycloak.start();
    }

    void stop() {
        keycloak.stop();
        network.close();
    }

    /** The environment that points a server container at this Keycloak. */
    Map<String, String> serverEnvironment(String scopesSupported, String requiredScopes) {
        return Map.of("MCP_AUTH_ENABLED", "true", "MCP_AUTH_RESOURCE", RESOURCE, "MCP_AUTH_ISSUER", issuer, "MCP_AUTH_JWKS_URI", "http://keycloak:8080/realms/mcp/protocol/openid-connect/certs", "MCP_AUTH_JWKS_ALLOW_HTTP", "true", "MCP_AUTH_AUTHORIZATION_SERVERS", issuer, "MCP_AUTH_SCOPES_SUPPORTED", scopesSupported, "MCP_AUTH_REQUIRED_SCOPES", requiredScopes);
    }

    /** Client-credentials grant; the scopes are space separated, as in the OAuth request. */
    String token(String clientId, String scope) throws IOException, InterruptedException {
        String form = "grant_type=client_credentials&client_id=" + enc(clientId) + "&client_secret=" + enc(SECRET) + "&scope=" + enc(scope);
        HttpRequest request = HttpRequest.newBuilder(URI.create(issuer + "/protocol/openid-connect/token")).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "Token request for " + clientId + " failed: " + response.body());
        JsonObject body = Json.createReader(new StringReader(response.body())).readObject();
        return body.getString("access_token");
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
