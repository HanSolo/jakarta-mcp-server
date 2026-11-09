package eu.hansolo.mcp.it;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Arguments checked against a nested {@code oneOf} schema on a real server: valid calls reach the tool with their values intact,
 * invalid ones are refused with {@code -32602} before the tool runs, and the message does not echo what the client sent.
 */
class ArgumentValidationIT {

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
    void aValidRectangleReachesTheTool() throws Exception {
        JsonObject result = ok(shape("rect", Json.createObjectBuilder().add("width", 3).add("height", 4)), null);
        assertEquals(12.0, result.getJsonObject("structuredContent").getJsonNumber("area").doubleValue(), 0.0001);
    }

    @Test
    void aValidCircleWithTagsReachesTheTool() throws Exception {
        JsonObject result = ok(shape("circle", Json.createObjectBuilder().add("radius", 2)), Json.createArrayBuilder().add("round").add("blue"));
        assertEquals(Math.PI * 4, result.getJsonObject("structuredContent").getJsonNumber("area").doubleValue(), 0.0001);
    }

    @Test
    void anIntegralNumberWrittenWithADecimalPointStillCountsAsAnInteger() throws Exception {
        JsonObject args = Json.createReader(new java.io.StringReader("{\"shape\":{\"kind\":\"rect\",\"width\":3.0,\"height\":4}}")).readObject();
        McpClient.Reply reply = client.toolsCall("shape_area", args, null);
        assertEquals(200, reply.status(), reply.body());
        assertFalse(reply.json().containsKey("error"), reply.body());
    }

    @Test
    void aMissingRequiredPropertyIsRefused() throws Exception {
        invalid(Json.createObjectBuilder().add("tags", Json.createArrayBuilder().add("x")).build());
    }

    @Test
    void aShapeThatMatchesNeitherBranchIsRefused() throws Exception {
        invalid(args(Json.createObjectBuilder().add("kind", "triangle").add("base", 3).build(), null));
    }

    @Test
    void aWrongTypeInsideTheNestedObjectIsRefused() throws Exception {
        invalid(args(Json.createObjectBuilder().add("kind", "rect").add("width", "wide").add("height", 4).build(), null));
    }

    @Test
    void aFractionWhereAnIntegerIsRequiredIsRefused() throws Exception {
        JsonObject args = Json.createReader(new java.io.StringReader("{\"shape\":{\"kind\":\"rect\",\"width\":2.5,\"height\":4}}")).readObject();
        invalid(args);
    }

    @Test
    void aValueBelowTheMinimumIsRefused() throws Exception {
        invalid(args(Json.createObjectBuilder().add("kind", "circle").add("radius", 0).build(), null));
    }

    @Test
    void anUnknownPropertyIsRefused() throws Exception {
        invalid(args(Json.createObjectBuilder().add("kind", "circle").add("radius", 1).add("color", "red").build(), null));
    }

    @Test
    void tagsThatBreakThePatternTheLimitOrUniquenessAreRefused() throws Exception {
        JsonObject circle = Json.createObjectBuilder().add("kind", "circle").add("radius", 1).build();
        invalid(args(circle, Json.createArrayBuilder().add("UPPER")));
        invalid(args(circle, Json.createArrayBuilder().add("a").add("b").add("c").add("d")));
        invalid(args(circle, Json.createArrayBuilder().add("same").add("same")));
    }

    @Test
    void theErrorDoesNotEchoWhatTheClientSent() throws Exception {
        McpClient.Reply reply = client.toolsCall("shape_area", args(Json.createObjectBuilder().add("kind", "rect").add("width", "secret-value-123").add("height", 4).build(), null), null);
        assertFalse(reply.body().contains("secret-value-123"), reply.body());
    }

    private static JsonObject shape(String kind, jakarta.json.JsonObjectBuilder fields) {
        return fields.add("kind", kind).build();
    }

    private static JsonObject args(JsonObject shape, JsonArrayBuilder tags) {
        jakarta.json.JsonObjectBuilder builder = Json.createObjectBuilder().add("shape", shape);
        if (tags != null) {
            builder.add("tags", tags);
        }
        return builder.build();
    }

    private static JsonObject ok(JsonObject shape, JsonArrayBuilder tags) throws Exception {
        McpClient.Reply reply = client.toolsCall("shape_area", args(shape, tags), null);
        assertEquals(200, reply.status(), reply.body());
        JsonObject json = reply.json();
        assertFalse(json.containsKey("error"), reply.body());
        return json.getJsonObject("result");
    }

    private static void invalid(JsonObject arguments) throws Exception {
        McpClient.Reply reply = client.toolsCall("shape_area", arguments, null);
        assertTrue(reply.json().containsKey("error"), "accepted: " + reply.body());
        assertEquals(-32602, reply.json().getJsonObject("error").getInt("code"), reply.body());
    }
}
