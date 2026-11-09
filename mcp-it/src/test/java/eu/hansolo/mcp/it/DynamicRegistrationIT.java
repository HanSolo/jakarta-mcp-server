package eu.hansolo.mcp.it;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tools added and removed while the server runs ({@code McpRegistrations}), driven through the fixture's admin endpoint the way
 * application code would drive it. The point is the whole chain on a real server: the registry changes, a client that listens is
 * told, and {@code tools/list} and {@code tools/call} agree with it.
 */
class DynamicRegistrationIT {

    private static GenericContainer<?> container;
    private static McpClient           client;


    @BeforeAll
    static void start() {
        Assumptions.assumeTrue(ServerFixture.dockerAvailable(), "Docker is not available: skipping integration tests");
        container = ServerFixture.create(ServerFixture.fixtureWar(), Map.of(), false);
        container.start();
        client = new McpClient(container.getHost(), container.getMappedPort(ServerFixture.httpPort()));
        ServerFixture.awaitDeployed(container, client);
    }

    @AfterAll
    static void stop() {
        if (container != null) {
            container.stop();
        }
    }


    @Test
    void anAddedToolIsListedCallableAndAnnounced() throws Exception {
        try (McpClient.Events events = client.openListen(501, Json.createObjectBuilder().add("toolsListChanged", true).build(), null, "application/json, text/event-stream")) {
            assertEquals("notifications/subscriptions/acknowledged", events.nextWithin(Duration.ofSeconds(20)).json().getString("method"));
            assertFalse(listed("dyn_added"));

            assertEquals(204, client.request("POST", "/admin/tools/dyn_added", null).status());

            assertEquals("notifications/tools/list_changed", events.nextWithin(Duration.ofSeconds(20)).json().getString("method"));
            assertTrue(listed("dyn_added"));
            JsonObject result = client.toolsCall("dyn_added", Json.createObjectBuilder().build(), null).json().getJsonObject("result");
            assertEquals("hello from dyn_added", result.getJsonArray("content").getJsonObject(0).getString("text"));
        } finally {
            client.request("DELETE", "/admin/tools/dyn_added", null);
        }
    }

    @Test
    void aRemovedToolDisappearsAndCanNoLongerBeCalled() throws Exception {
        assertEquals(204, client.request("POST", "/admin/tools/dyn_removed", null).status());
        assertTrue(listed("dyn_removed"));
        try (McpClient.Events events = client.openListen(502, Json.createObjectBuilder().add("toolsListChanged", true).build(), null, "application/json, text/event-stream")) {
            assertEquals("notifications/subscriptions/acknowledged", events.nextWithin(Duration.ofSeconds(20)).json().getString("method"));

            assertEquals(204, client.request("DELETE", "/admin/tools/dyn_removed", null).status());

            assertEquals("notifications/tools/list_changed", events.nextWithin(Duration.ofSeconds(20)).json().getString("method"));
        }
        assertFalse(listed("dyn_removed"));
        McpClient.Reply call = client.toolsCall("dyn_removed", Json.createObjectBuilder().build(), null);
        assertTrue(call.json().containsKey("error"), call.body());
    }

    @Test
    void removingAToolThatIsNotThereIsA404() throws Exception {
        assertEquals(404, client.request("DELETE", "/admin/tools/never_existed", null).status());
    }

    @Test
    void aTakenNameIsRefused() throws Exception {
        assertEquals(204, client.request("POST", "/admin/tools/dyn_twice", null).status());
        try {
            assertEquals(409, client.request("POST", "/admin/tools/dyn_twice", null).status());
        } finally {
            client.request("DELETE", "/admin/tools/dyn_twice", null);
        }
    }

    @Test
    void theToolsFoundAtStartupAreStillThere() throws Exception {
        assertTrue(listed("shape_area"));
    }

    private static boolean listed(String name) throws Exception {
        JsonArray tools = client.toolsList(null).json().getJsonObject("result").getJsonArray("tools");
        return tools.stream().map(JsonObject.class::cast).anyMatch(t -> name.equals(t.getString("name")));
    }
}
