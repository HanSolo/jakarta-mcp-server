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

/** Extension negotiation on a real server: what {@code server/discover} announces and what a tool sees of the client's declaration. */
class ExtensionsIT {

    private static final String ID = "com.example/it-fixture";

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
    void discoverAnnouncesTheExtensionWithItsSettings() throws Exception {
        JsonObject extensions = client.discover(null).json().getJsonObject("result").getJsonObject("capabilities").getJsonObject("extensions");
        assertEquals(1, extensions.getJsonObject(ID).getInt("level"));
    }

    @Test
    void aToolSeesTheSettingsTheClientDeclared() throws Exception {
        JsonObject caps = declared(Json.createObjectBuilder().add("flavor", "x").build());
        JsonObject structured = call("ext_probe", caps).json().getJsonObject("result").getJsonObject("structuredContent");
        assertTrue(structured.getBoolean("declared"));
        assertEquals("x", structured.getJsonObject("settings").getString("flavor"));
    }

    @Test
    void aToolFallsBackWhenTheClientDeclaredNothing() throws Exception {
        JsonObject structured = call("ext_probe", Json.createObjectBuilder().build()).json().getJsonObject("result").getJsonObject("structuredContent");
        assertFalse(structured.getBoolean("declared"));
    }

    @Test
    void aMandatoryExtensionIsRefusedWith400And32021() throws Exception {
        McpClient.Reply reply = call("ext_required", Json.createObjectBuilder().build());
        assertEquals(400, reply.status(), reply.body());
        JsonObject error = reply.json().getJsonObject("error");
        assertEquals(-32021, error.getInt("code"));
        assertTrue(error.getJsonObject("data").getJsonObject("requiredCapabilities").getJsonObject("extensions").containsKey(ID));
    }

    @Test
    void aMandatoryExtensionPassesWhenDeclared() throws Exception {
        assertEquals(200, call("ext_required", declared(Json.createObjectBuilder().build())).status());
    }

    private static McpClient.Reply call(String tool, JsonObject capabilities) throws Exception {
        return client.rpc("tools/call", 1, Json.createObjectBuilder().add("name", tool), tool, null, capabilities);
    }

    private static JsonObject declared(JsonObject settings) {
        return Json.createObjectBuilder().add("extensions", Json.createObjectBuilder().add(ID, settings)).build();
    }
}
