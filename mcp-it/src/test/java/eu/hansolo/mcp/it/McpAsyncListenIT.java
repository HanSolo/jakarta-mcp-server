package eu.hansolo.mcp.it;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * subscriptions/listen with {@code mcp.listen.async=true}: the streams run on virtual threads instead of holding the server's
 * request threads. The keep-alive is shortened so a vanished client is noticed quickly and the idle tests stay short.
 */
class McpAsyncListenIT {

    private static final String BOTH = "application/json, text/event-stream";

    private static GenericContainer<?> container;
    private static McpClient           client;


    @BeforeAll
    static void start() {
        Assumptions.assumeTrue(ServerFixture.dockerAvailable(), "Docker is not available: skipping integration tests");
        container = ServerFixture.create(Map.of("MCP_LISTEN_ASYNC", "true", "MCP_LISTEN_KEEPALIVE_MS", "1000", "MCP_LISTEN_MAX", "200", "MCP_LISTEN_MAX_PER_CALLER", "200"), false);
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
    void anAsyncStreamStillGetsTheAcknowledgementAndTheNotification() throws Exception {
        try (McpClient.Events events = client.openListen(201, toolsFilter(), null, BOTH)) {
            assertEquals(200, events.status());
            assertTrue(events.contentType().startsWith("text/event-stream"), events.contentType());
            assertEquals("notifications/subscriptions/acknowledged", events.nextWithin(Duration.ofSeconds(20)).json().getString("method"));
            client.toolsCall("refresh_tools", Json.createObjectBuilder().build(), null);
            assertEquals("notifications/tools/list_changed", events.nextWithin(Duration.ofSeconds(20)).json().getString("method"));
        }
    }

    @Test
    void manyOpenStreamsDoNotUseUpTheRequestThreads() throws Exception {
        int streams = 60;   // well above the HTTP thread pool of a stock server
        List<McpClient.Events> open = new ArrayList<>();
        try {
            for (int i = 0; i < streams; i++) {
                McpClient.Events events = client.openListen(300 + i, toolsFilter(), null, BOTH);
                open.add(events);
                assertEquals(200, events.status(), "stream " + i);
            }
            for (McpClient.Events events : open) {
                assertEquals("notifications/subscriptions/acknowledged", events.nextWithin(Duration.ofSeconds(20)).json().getString("method"));
            }
            // ordinary requests must still be answered while all of those streams are open
            for (int i = 0; i < 10; i++) {
                assertEquals(200, client.discover(null).status(), "request " + i + " while " + streams + " streams are open");
            }
            JsonObject sum = client.toolsCall("add", Json.createObjectBuilder().add("a", 2).add("b", 3).build(), null).json().getJsonObject("result");
            assertEquals(false, sum.getBoolean("isError"));

            client.toolsCall("refresh_tools", Json.createObjectBuilder().build(), null);
            for (McpClient.Events events : open) {
                assertEquals("notifications/tools/list_changed", events.nextWithin(Duration.ofSeconds(20)).json().getString("method"));
            }
        } finally {
            for (McpClient.Events events : open) {
                events.close();
            }
        }
    }

    /**
     * Suspending a request starts a servlet async cycle that servers end after a default timeout (30 s on common servers) unless it
     * is switched off. A stream must live longer than that.
     */
    @Test
    void aStreamOutlivesTheContainersDefaultAsyncTimeout() throws Exception {
        try (McpClient.Events events = client.openListen(202, toolsFilter(), null, BOTH)) {
            assertEquals("notifications/subscriptions/acknowledged", events.nextWithin(Duration.ofSeconds(20)).json().getString("method"));
            Thread.sleep(Duration.ofSeconds(40).toMillis());
            client.toolsCall("refresh_tools", Json.createObjectBuilder().build(), null);
            McpClient.Event event = events.nextWithin(Duration.ofSeconds(20));
            assertEquals("notifications/tools/list_changed", event.json().getString("method"), "the stream was cut after 40 s: " + event.json());
        }
    }

    private static JsonObject toolsFilter() {
        return Json.createObjectBuilder().add("toolsListChanged", true).build();
    }
}
