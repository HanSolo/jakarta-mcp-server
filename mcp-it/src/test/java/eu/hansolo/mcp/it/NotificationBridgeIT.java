package eu.hansolo.mcp.it;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Two instances of the fixture on one Docker network, each told about the other through the fixture's HTTP bridge. A client that
 * listens on instance B must hear about a change announced on instance A, exactly once, and nothing must bounce back.
 */
class NotificationBridgeIT {

    private static final String BOTH = "application/json, text/event-stream";

    private static Network             network;
    private static GenericContainer<?> a;
    private static GenericContainer<?> b;
    private static McpClient           clientA;
    private static McpClient           clientB;


    @BeforeAll
    static void start() {
        Assumptions.assumeTrue(ServerFixture.dockerAvailable(), "Docker is not available: skipping integration tests");
        network = Network.newNetwork();
        int port = ServerFixture.httpPort();
        a = ServerFixture.create(ServerFixture.fixtureWar(), Map.of("MCP_IT_PEERS", "http://mcp-b:" + port), false).withNetwork(network).withNetworkAliases("mcp-a");
        b = ServerFixture.create(ServerFixture.fixtureWar(), Map.of("MCP_IT_PEERS", "http://mcp-a:" + port), false).withNetwork(network).withNetworkAliases("mcp-b");
        a.start();
        b.start();
        clientA = new McpClient(a.getHost(), a.getMappedPort(port));
        clientB = new McpClient(b.getHost(), b.getMappedPort(port));
        ServerFixture.awaitDeployed(a, clientA);
        ServerFixture.awaitDeployed(b, clientB);
    }

    @AfterAll
    static void stop() {
        for (GenericContainer<?> container : new GenericContainer<?>[] {a, b}) {
            if (container != null) {
                container.stop();
            }
        }
        if (network != null) {
            network.close();
        }
    }


    @Test
    void theTwoInstancesAreDifferentInstances() throws Exception {
        assertNotEquals(instanceId(clientA), instanceId(clientB));
    }

    @Test
    void aChangeAnnouncedOnAReachesTheListenersOnBAndOnAExactlyOnce() throws Exception {
        try (McpClient.Events onA = clientA.openListen(601, toolsFilter(), null, BOTH);
             McpClient.Events onB = clientB.openListen(602, toolsFilter(), null, BOTH)) {
            assertEquals("notifications/subscriptions/acknowledged", onA.nextWithin(Duration.ofSeconds(20)).json().getString("method"));
            assertEquals("notifications/subscriptions/acknowledged", onB.nextWithin(Duration.ofSeconds(20)).json().getString("method"));

            assertEquals(204, clientA.request("POST", "/admin/announce/tools", null).status());

            assertEquals("notifications/tools/list_changed", onA.nextWithin(Duration.ofSeconds(20)).json().getString("method"));
            assertEquals("notifications/tools/list_changed", onB.nextWithin(Duration.ofSeconds(20)).json().getString("method"));

            // B passed nothing on and A ignored nothing it should have heard: no second notification on either side
            assertThrows(AssertionError.class, () -> onA.nextWithin(Duration.ofSeconds(4)), "A heard its own change again");
            assertThrows(AssertionError.class, () -> onB.nextWithin(Duration.ofSeconds(1)), "B heard the change twice");
        }
    }

    @Test
    void theOtherDirectionWorksToo() throws Exception {
        try (McpClient.Events onA = clientA.openListen(603, toolsFilter(), null, BOTH)) {
            assertEquals("notifications/subscriptions/acknowledged", onA.nextWithin(Duration.ofSeconds(20)).json().getString("method"));
            assertEquals(204, clientB.request("POST", "/admin/announce/tools", null).status());
            assertEquals("notifications/tools/list_changed", onA.nextWithin(Duration.ofSeconds(20)).json().getString("method"));
        }
    }

    @Test
    void aToolRegisteredOnAIsNotAnnouncedOnBBecauseRegistrationsStayLocal() throws Exception {
        try (McpClient.Events onB = clientB.openListen(604, toolsFilter(), null, BOTH)) {
            assertEquals("notifications/subscriptions/acknowledged", onB.nextWithin(Duration.ofSeconds(20)).json().getString("method"));
            assertEquals(204, clientA.request("POST", "/admin/tools/only_on_a", null).status());
            try {
                assertThrows(AssertionError.class, () -> onB.nextWithin(Duration.ofSeconds(4)), "B was told about a tool it does not have");
                assertEquals(false, clientB.toolsList(null).body().contains("only_on_a"));
            } finally {
                clientA.request("DELETE", "/admin/tools/only_on_a", null);
            }
        }
    }

    @Test
    void aPeerRefusesAGarbledChange() throws Exception {
        McpClient.Reply reply = clientB.request("POST", "/admin/changes", "{\"v\":2,\"o\":\"x\",\"k\":\"tools\"}");
        assertEquals(400, reply.status(), reply.body());
    }

    private static String instanceId(McpClient client) throws Exception {
        return client.request("GET", "/admin/instance", null).json().getString("instanceId");
    }

    private static JsonObject toolsFilter() {
        return Json.createObjectBuilder().add("toolsListChanged", true).build();
    }
}
