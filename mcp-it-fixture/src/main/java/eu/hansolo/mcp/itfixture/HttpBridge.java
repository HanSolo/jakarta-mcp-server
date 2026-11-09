package eu.hansolo.mcp.itfixture;

import jakarta.enterprise.context.ApplicationScoped;
import eu.hansolo.mcp.server.McpChange;
import eu.hansolo.mcp.server.McpNotificationBridge;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * Hands every change to the other instances with a plain HTTP POST to their {@code /admin/changes}. The peers come from the
 * environment variable {@code MCP_IT_PEERS}, a comma separated list of base URLs such as {@code http://server-b:8080}. With the
 * variable unset the bridge has nobody to tell and does nothing.
 *
 * <p>Real deployments would use a message topic instead; this is the smallest thing that moves a change between two containers.
 */
@ApplicationScoped
public class HttpBridge implements McpNotificationBridge {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();


    @Override
    public void publish(McpChange change) throws Exception {
        for (String peer : peers()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(peer + "/admin/changes")).timeout(Duration.ofSeconds(10)).header("Content-Type", "text/plain").POST(HttpRequest.BodyPublishers.ofString(change.encode())).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 204) {
                throw new IllegalStateException("Peer " + peer + " answered " + response.statusCode() + ": " + response.body());
            }
        }
    }

    private static List<String> peers() {
        String value = System.getenv("MCP_IT_PEERS");
        return value == null || value.isBlank() ? List.of() : Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
