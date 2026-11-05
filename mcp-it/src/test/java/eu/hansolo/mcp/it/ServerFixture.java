package eu.hansolo.mcp.it;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Starts the example WAR in a container of the application server chosen with {@code -Dmcp.server=payara|wildfly|liberty} (default
 * payara). {@code -Dmcp.image=...} overrides the image; for Payara the older {@code -Dpayara.image} still works.
 */
final class ServerFixture {

    /** The servers the suite can run against. Only Payara has been verified by the author; the others are untested defaults. */
    enum Server {

        PAYARA("payara/micro:latest", 8080, "/opt/payara/deployments/ROOT.war"),
        WILDFLY("quay.io/wildfly/wildfly:latest-jdk21", 8080, "/opt/jboss/wildfly/standalone/deployments/ROOT.war"),
        LIBERTY("icr.io/appcafe/open-liberty:full-java21-openj9-ubi-minimal", 9080, "/opt/ol/wlp/usr/servers/defaultServer/apps/ROOT.war");

        private final String defaultImage;
        private final int    httpPort;
        private final String warPath;


        Server(String defaultImage, int httpPort, String warPath) {
            this.defaultImage = defaultImage;
            this.httpPort     = httpPort;
            this.warPath      = warPath;
        }
    }


    private static final Server SERVER = selected();


    private ServerFixture() {
    }


    static int httpPort() {
        return SERVER.httpPort;
    }

    private static Server selected() {
        String name = System.getProperty("mcp.server", "payara").trim().toUpperCase(java.util.Locale.ROOT);
        try {
            return Server.valueOf(name.isEmpty() ? "PAYARA" : name);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Unknown -Dmcp.server '" + name.toLowerCase(java.util.Locale.ROOT) + "': use payara, wildfly or liberty");
        }
    }

    private static String image() {
        String image = System.getProperty("mcp.image", "");
        if (image.isBlank() && SERVER == Server.PAYARA) {
            image = System.getProperty("payara.image", "");
        }
        return image.isBlank() ? SERVER.defaultImage : image.trim();
    }

    static boolean dockerAvailable() {
        try {
            boolean available = DockerClientFactory.instance().isDockerAvailable();
            if (!available) {
                System.err.println("[mcp-it] Testcontainers reports Docker as unavailable (DOCKER_HOST=" + System.getenv("DOCKER_HOST") + "). Integration tests are skipped.");
            }
            return available;
        } catch (Throwable t) {
            System.err.println("[mcp-it] The Docker check failed, integration tests are skipped. Cause: " + t);
            t.printStackTrace();
            return false;
        }
    }

    /** Creates (but does not start) a container with the example WAR deployed at the context root (the file is named ROOT.war, which all three servers map to "/"). */
    static GenericContainer<?> create(Map<String, String> environment, boolean reachHost) {
        return create(Path.of(System.getProperty("mcp.war", "../mcp-example/target/mcp-example.war")), environment, reachHost);
    }

    /** Same, for any WAR (the tickets example, say). */
    static GenericContainer<?> create(Path warPath, Map<String, String> environment, boolean reachHost) {
        Path war = warPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(war)) {
            throw new IllegalStateException("WAR not found at " + war + ": run the build from the parent project so mcp-example is packaged first");
        }
        GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(image())).withCopyFileToContainer(MountableFile.forHostPath(war), SERVER.warPath).withExposedPorts(SERVER.httpPort).withEnv(environment).waitingFor(Wait.forListeningPort()).withStartupTimeout(Duration.ofMinutes(4));
        if (SERVER == Server.LIBERTY) {
            // the stock server.xml enables no Jakarta EE features and serves no application
            container.withCopyFileToContainer(MountableFile.forClasspathResource("liberty/server.xml"), "/opt/ol/wlp/usr/servers/defaultServer/server.xml");
        }
        if (reachHost) {
            container.withAccessToHost(true);
        }
        return container;
    }

    /** Polls the endpoint until the application answers (200, or 401 when authentication is on), because the port opens before deployment finishes. */
    static void awaitDeployed(GenericContainer<?> container, McpClient client) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(180));
        String last = "no response";
        while (Instant.now().isBefore(deadline)) {
            try {
                McpClient.Reply reply = client.discover(null);
                if (reply.status() == 200 || reply.status() == 401) {
                    return;
                }
                last = "HTTP " + reply.status() + " " + reply.body();
            } catch (Exception e) {
                last = e.toString();
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for deployment", e);
            }
        }
        throw new IllegalStateException("The application did not come up within 180s (last: " + last + ")\n--- container log ---\n" + container.getLogs());
    }
}
