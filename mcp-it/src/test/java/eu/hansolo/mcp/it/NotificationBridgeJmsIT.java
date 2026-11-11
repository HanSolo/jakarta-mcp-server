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

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two instances of the fixture and an Artemis broker on one Docker network. The instances know nothing of each other (no HTTP peers);
 * the only way a change announced on A can reach the listeners on B is the library's JMS bridge and the broker's topic.
 */
class NotificationBridgeJmsIT {

    private static final String BOTH = "application/json, text/event-stream";

    private static Network             network;
    private static GenericContainer<?> artemis;
    private static GenericContainer<?> a;
    private static GenericContainer<?> b;
    private static McpClient           clientA;
    private static McpClient           clientB;


    @BeforeAll
    static void start() {
        Assumptions.assumeTrue(ServerFixture.dockerAvailable(), "Docker is not available: skipping integration tests");
        network = Network.newNetwork();
        artemis = new GenericContainer<>(DockerImageName.parse("apache/activemq-artemis:latest"))
            .withNetwork(network).withNetworkAliases("artemis")
            .withEnv("ARTEMIS_USER", "mcp").withEnv("ARTEMIS_PASSWORD", "mcp")
            .waitingFor(Wait.forLogMessage(".*Server is now (live|active).*\\n", 1))
            .withStartupTimeout(Duration.ofMinutes(3));
        artemis.start();

        Map<String, String> environment = Map.of("MCP_IT_JMS_URL", "tcp://artemis:61616", "MCP_IT_JMS_USER", "mcp", "MCP_IT_JMS_PASSWORD", "mcp");
        int port = ServerFixture.httpPort();
        a = ServerFixture.create(ServerFixture.fixtureWar(), environment, false).withNetwork(network).withNetworkAliases("mcp-a");
        b = ServerFixture.create(ServerFixture.fixtureWar(), environment, false).withNetwork(network).withNetworkAliases("mcp-b");
        a.start();
        b.start();
        clientA = new McpClient(a.getHost(), a.getMappedPort(port));
        clientB = new McpClient(b.getHost(), b.getMappedPort(port));
        ServerFixture.awaitDeployed(a, clientA);
        ServerFixture.awaitDeployed(b, clientB);
        // a non-durable subscription only hears what is published after it exists: wait until both hold theirs
        for (McpClient client : new McpClient[] {clientA, clientB}) {
            McpClient.Reply ready = null;
            try {
                ready = client.request("GET", "/admin/jms/ready", null);
            } catch (Exception e) {
                throw new IllegalStateException("The fixture did not answer /admin/jms/ready: " + e, e);
            }
            JsonObject state = ready.json();
            assertTrue(state.getBoolean("configured"), "MCP_IT_JMS_URL did not reach the fixture: " + ready.body());
            assertTrue(state.getBoolean("listening"), "The JMS bridge did not subscribe in time: " + ready.body() + "\n--- instance A ---\n" + tail(a) + "\n--- instance B ---\n" + tail(b) + "\n--- artemis ---\n" + tail(artemis));
        }
    }

    @AfterAll
    static void stop() {
        for (GenericContainer<?> container : new GenericContainer<?>[] {a, b, artemis}) {
            if (container != null) {
                container.stop();
            }
        }
        if (network != null) {
            network.close();
        }
    }


    @Test
    void aChangeAnnouncedOnAReachesTheListenersOnBAndOnAExactlyOnce() throws Exception {
        try (McpClient.Events onA = clientA.openListen(701, toolsFilter(), null, BOTH);
             McpClient.Events onB = clientB.openListen(702, toolsFilter(), null, BOTH)) {
            assertEquals("notifications/subscriptions/acknowledged", onA.nextWithin(Duration.ofSeconds(20)).json().getString("method"));
            assertEquals("notifications/subscriptions/acknowledged", onB.nextWithin(Duration.ofSeconds(20)).json().getString("method"));

            assertEquals(204, clientA.request("POST", "/admin/announce/tools", null).status());

            assertEquals("notifications/tools/list_changed", onA.nextWithin(Duration.ofSeconds(20)).json().getString("method"));
            assertEquals("notifications/tools/list_changed", onB.nextWithin(Duration.ofSeconds(20)).json().getString("method"));

            // the sender hears its own message from the topic and ignores it; B tells its clients once
            assertThrows(AssertionError.class, () -> onA.nextWithin(Duration.ofSeconds(4)), "A heard its own change again");
            assertThrows(AssertionError.class, () -> onB.nextWithin(Duration.ofSeconds(1)), "B heard the change twice");
        }
    }

    @Test
    void theOtherDirectionWorksToo() throws Exception {
        try (McpClient.Events onA = clientA.openListen(703, toolsFilter(), null, BOTH)) {
            assertEquals("notifications/subscriptions/acknowledged", onA.nextWithin(Duration.ofSeconds(20)).json().getString("method"));
            assertEquals(204, clientB.request("POST", "/admin/announce/tools", null).status());
            assertEquals("notifications/tools/list_changed", onA.nextWithin(Duration.ofSeconds(20)).json().getString("method"));
        }
    }

    @Test
    void aToolRegisteredOnAIsNotAnnouncedOnBBecauseRegistrationsStayLocal() throws Exception {
        try (McpClient.Events onB = clientB.openListen(704, toolsFilter(), null, BOTH)) {
            assertEquals("notifications/subscriptions/acknowledged", onB.nextWithin(Duration.ofSeconds(20)).json().getString("method"));
            assertEquals(204, clientA.request("POST", "/admin/tools/jms_only_on_a", null).status());
            try {
                assertThrows(AssertionError.class, () -> onB.nextWithin(Duration.ofSeconds(4)), "B was told about a tool it does not have");
            } finally {
                clientA.request("DELETE", "/admin/tools/jms_only_on_a", null);
            }
        }
    }


    private static String tail(GenericContainer<?> container) {
        String logs = container.getLogs();
        return logs.length() <= 6000 ? logs : logs.substring(logs.length() - 6000);
    }

    private static JsonObject toolsFilter() {
        return Json.createObjectBuilder().add("toolsListChanged", true).build();
    }
}
