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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A tool that returns a plain bean (getters, no record): structured content built from the getters. */
class BeanResultIT {

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
    void aBeanBecomesStructuredContent() throws Exception {
        McpClient.Reply reply = client.toolsCall("bean_duck", Json.createObjectBuilder().build(), null);
        assertEquals(200, reply.status(), reply.body());
        JsonObject result = reply.json().getJsonObject("result");
        assertFalse(result.getBoolean("isError", false), reply.body());
        JsonObject duck = result.getJsonObject("structuredContent");
        assertEquals("Donald", duck.getString("name"));
        assertEquals("2026-11-24", duck.getString("firstSwim"));
        assertTrue(duck.getBoolean("hungry"));
        assertEquals(7, duck.getInt("age"));
        assertEquals("Duckburg Pond", duck.getJsonObject("pond").getString("name"));
        assertEquals(2, duck.getJsonArray("visited").size());
        assertFalse(duck.containsKey("nickname"), "null is left out");
        assertFalse(duck.containsKey("owner"), "an empty Optional is left out");
    }

    @Test
    void theToolAdvertisesAnOutputSchemaWithTheProperties() throws Exception {
        McpClient.Reply reply = client.toolsList(null);
        assertEquals(200, reply.status(), reply.body());
        JsonObject tool = reply.json().getJsonObject("result").getJsonArray("tools").getValuesAs(JsonObject.class).stream()
                               .filter(t -> t.getString("name").equals("bean_duck")).findFirst().orElseThrow();
        JsonObject properties = tool.getJsonObject("outputSchema").getJsonObject("properties");
        assertEquals("integer", properties.getJsonObject("age").getString("type"));
        assertEquals("Day of the first swim", properties.getJsonObject("firstSwim").getString("description"));
        assertEquals("array", properties.getJsonObject("visited").getString("type"));
    }
}
