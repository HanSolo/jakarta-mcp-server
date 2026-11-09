package eu.hansolo.mcp.it;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Skills extension on a real server: announcement, listing, lookup, reading with digest verification, directories. */
class SkillsIT {

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


    private static McpClient.Reply rpc(String method, JsonObjectBuilder params, String name) throws Exception {
        return client.rpc(method, 1, params, name, null, Json.createObjectBuilder().build());
    }

    private static JsonObject ok(McpClient.Reply reply) {
        assertEquals(200, reply.status(), reply.body());
        return reply.json().getJsonObject("result");
    }

    private static String sha256(byte[] bytes) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }


    @Test
    void discoverAnnouncesTheSkillsExtension() throws Exception {
        JsonObject capabilities = client.discover(null).json().getJsonObject("result").getJsonObject("capabilities");
        assertTrue(capabilities.containsKey("resources"));
        assertTrue(capabilities.getJsonObject("extensions").getJsonObject("io.modelcontextprotocol/skills").getBoolean("directoryRead"));
    }

    @Test
    void listHasBothSkillsWithManifests() throws Exception {
        JsonArray skills = ok(rpc("skills/list", Json.createObjectBuilder(), null)).getJsonArray("skills");
        assertEquals(2, skills.size());
        assertEquals("skill://code-review/SKILL.md", skills.getJsonObject(0).getString("uri"));
        assertEquals("skill://greeter/SKILL.md", skills.getJsonObject(1).getString("uri"));
        assertEquals("MIT", skills.getJsonObject(1).getJsonObject("frontmatter").getString("license"));
        assertEquals(2, skills.getJsonObject(1).getJsonArray("resources").size());
    }

    @Test
    void everyFileMatchesItsManifestEntry() throws Exception {
        JsonArray skills = ok(rpc("skills/list", Json.createObjectBuilder(), null)).getJsonArray("skills");
        for (int i = 0; i < skills.size(); i++) {
            JsonArray manifest = skills.getJsonObject(i).getJsonArray("resources");
            for (int j = 0; j < manifest.size(); j++) {
                JsonObject entry = manifest.getJsonObject(j);
                String uri  = entry.getString("uri");
                JsonObject content = ok(rpc("resources/read", Json.createObjectBuilder().add("uri", uri), uri)).getJsonArray("contents").getJsonObject(0);
                byte[] bytes = content.getString("text").getBytes(StandardCharsets.UTF_8);
                assertEquals(entry.getInt("size"), bytes.length, uri);
                assertEquals(entry.getString("digest"), sha256(bytes), uri);
            }
        }
    }

    @Test
    void getFindsASkillByUriAndRefusesAnUnknownOne() throws Exception {
        JsonObject skill = ok(rpc("skills/get", Json.createObjectBuilder().add("uri", "skill://greeter/SKILL.md"), null)).getJsonObject("skill");
        assertEquals("greeter", skill.getJsonObject("frontmatter").getString("name"));
        McpClient.Reply unknown = rpc("skills/get", Json.createObjectBuilder().add("uri", "skill://nope/SKILL.md"), null);
        assertEquals(-32602, unknown.json().getJsonObject("error").getInt("code"));
    }

    @Test
    void directoriesListTheirChildren() throws Exception {
        JsonArray root = ok(rpc("resources/directory/read", Json.createObjectBuilder().add("uri", "skill://greeter"), null)).getJsonArray("resources");
        assertEquals(2, root.size());
        assertEquals("SKILL.md", root.getJsonObject(0).getString("name"));
        assertEquals("inode/directory", root.getJsonObject(1).getString("mimeType"));
        JsonArray refs = ok(rpc("resources/directory/read", Json.createObjectBuilder().add("uri", "skill://greeter/references"), null)).getJsonArray("resources");
        assertEquals("skill://greeter/references/tone.md", refs.getJsonObject(0).getString("uri"));
    }
}
