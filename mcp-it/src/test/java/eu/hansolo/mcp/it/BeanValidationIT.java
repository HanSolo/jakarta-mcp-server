package eu.hansolo.mcp.it;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bean Validation constraints on tool parameters. {@code @NotBlank}, {@code @Size}, {@code @Min} and {@code @Max} are in the schema,
 * so a bad value is refused with -32602 before the tool runs. {@code @AssertTrue} cannot be written as a schema keyword: the server's
 * own method validation for CDI beans (required of a Jakarta EE 11 container) throws a {@code ConstraintViolationException}, and the
 * library shows its message to the model instead of the generic text.
 */
class BeanValidationIT {

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
    void validArgumentsReachTheTool() throws Exception {
        McpClient.Reply reply = book("Donald", 3);
        assertEquals(200, reply.status(), reply.body());
        assertFalse(reply.json().containsKey("error"), reply.body());
        assertEquals("booked Donald x3", reply.json().getJsonObject("result").getJsonArray("content").getJsonObject(0).getString("text"));
    }

    @Test
    void aNumberAboveMaxIsRefusedBeforeTheToolRuns() throws Exception {
        assertInvalid(book("Donald", 9), "times");
    }

    @Test
    void aBlankTextIsRefused() throws Exception {
        assertInvalid(book("   ", 1), "duck");
    }

    @Test
    void aTextAboveSizeIsRefused() throws Exception {
        assertInvalid(book("Duck with a very long name", 1), "duck");
    }

    @Test
    void aConstraintOnlyTheContainerKnowsShowsItsMessage() throws Exception {
        McpClient.Reply reply = client.toolsCall("validated_confirm", Json.createObjectBuilder().add("confirmed", false).build(), null);
        assertEquals(200, reply.status(), reply.body());
        JsonObject result = reply.json().getJsonObject("result");
        assertTrue(result.getBoolean("isError", false), "the container did not validate the method call: " + reply.body());
        String text = result.getJsonArray("content").getJsonObject(0).getString("text");
        assertNotEquals("Tool execution failed", text);
    }


    private static McpClient.Reply book(String duck, int times) throws Exception {
        return client.toolsCall("validated_book", Json.createObjectBuilder().add("duck", duck).add("times", times).build(), null);
    }

    private static void assertInvalid(McpClient.Reply reply, String argument) {
        assertTrue(reply.json().containsKey("error"), "accepted: " + reply.body());
        JsonObject error = reply.json().getJsonObject("error");
        assertEquals(-32602, error.getInt("code"), reply.body());
        assertTrue(error.getString("message").contains(argument), reply.body());
    }
}
