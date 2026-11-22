package eu.hansolo.mcp.it;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The CDI events {@code ToolCalled} and {@code ToolCompleted}: the fixture's {@code ToolEventObserver} writes them into the call log.
 * Synchronous observers run in the order of the events; the asynchronous one is checked by waiting for it.
 */
class ToolEventsIT {

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

    @BeforeEach
    void clearTheLog() throws Exception {
        assertEquals(204, client.request("DELETE", "/admin/calls", null).status());
    }


    @Test
    void aSuccessfulCallFiresCalledAndCompleted() throws Exception {
        client.toolsCall("bean_duck", Json.createObjectBuilder().build(), null);
        List<String> events = awaitAsync("bean_duck");
        assertEquals(List.of("event called bean_duck", "event completed bean_duck SUCCESS"), events.stream().filter(e -> e.startsWith("event ")).toList());
    }

    @Test
    void aThrowingToolIsCompletedAsAnErrorWithTheExceptionClass() throws Exception {
        client.toolsCall("fail_secret", Json.createObjectBuilder().add("name", "Scrooge").build(), null);
        List<String> events = awaitAsync("fail_secret");
        assertTrue(events.contains("event completed fail_secret ERROR java.lang.IllegalStateException"), events.toString());
        assertTrue(events.stream().noneMatch(e -> e.contains("hunter2")), "the message never reaches the event");
    }


    /** The log once the asynchronous observer has seen the tool. */
    private static List<String> awaitAsync(String tool) throws Exception {
        for (int i = 0; i < 50; i++) {
            List<String> log = log();
            if (log.contains("async completed " + tool)) {
                return log;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("the asynchronous observer did not run for " + tool + ": " + log());
    }

    private static List<String> log() throws Exception {
        McpClient.Reply reply = client.request("GET", "/admin/calls", null);
        assertEquals(200, reply.status(), reply.body());
        JsonArray array = Json.createReader(new java.io.StringReader(reply.body())).readArray();
        return array.getValuesAs(jakarta.json.JsonString.class).stream().map(jakarta.json.JsonString::getString).toList();
    }
}
