package eu.hansolo.mcp.it;

import jakarta.json.Json;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A streamed tools/call that reports no progress still gets keep-alive comment lines while it runs, and they reach the client on time. */
class KeepAliveIT {

    private static GenericContainer<?> container;
    private static McpClient           client;


    @BeforeAll
    static void start() {
        Assumptions.assumeTrue(ServerFixture.dockerAvailable(), "Docker is not available: skipping integration tests");
        container = ServerFixture.create(ServerFixture.fixtureWar(), Map.of("MCP_LISTEN_KEEPALIVE_MS", "200"), false);
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
    void aQuietCallGetsCommentsWhileItRuns() throws Exception {
        try (McpClient.Events events = client.openToolCall("quiet", Json.createObjectBuilder().add("millis", 2500).build(), "p1", "application/json, text/event-stream")) {
            assertEquals(200, events.status());
            List<McpClient.Event> all = events.all();
            assertEquals(1, all.size(), "only the result is an event");
            assertEquals("done", all.get(0).json().getJsonObject("result").getJsonArray("content").getJsonObject(0).getString("text"));
            assertTrue(events.comments() >= 5, "comments: " + events.comments());
            assertTrue(events.firstCommentMs() >= 0 && events.firstCommentMs() < 1000, "the first comment arrived after " + events.firstCommentMs() + " ms; the server buffers the stream");
            assertTrue(all.get(0).arrivedMs() >= 2400, "the result came at " + all.get(0).arrivedMs() + " ms");
        }
    }

    @Test
    void aShortCallGetsNoComment() throws Exception {
        try (McpClient.Events events = client.openToolCall("quiet", Json.createObjectBuilder().add("millis", 50).build(), "p2", "application/json, text/event-stream")) {
            assertEquals(1, events.all().size());
            assertEquals(0, events.comments());
        }
    }
}
