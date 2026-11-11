package eu.hansolo.mcp.server;

import eu.hansolo.mcp.api.McpSkill;
import eu.hansolo.mcp.api.McpToolHandler;
import eu.hansolo.mcp.protocol.ErrorCodes;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpSkillsTest {

    private static final String VERSION = "2026-07-28";
    private static final String MAIN    = "---\nname: code-review\ndescription: Review code.\n---\n\n# Code review\n";

    private final McpConfig config = new McpConfig(null, 1024, 5_000L, "test-server", "1.2.3", null);

    private McpRequestHandler withSkill() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("SKILL.md", MAIN);
        files.put("references/checklist.md", "# Checklist\n");
        return new McpRequestHandler(McpRegistry.of(List.<McpToolHandler>of(), List.of(McpSkill.of("code-review", files))), config);
    }

    private McpRequestHandler withoutSkill() {
        return new McpRequestHandler(McpRegistry.of(List.<McpToolHandler>of()), config);
    }

    // ---- discovery -------------------------------------------------------------------------------------------------

    @Test
    void discoverAnnouncesTheExtensionAndResources() {
        JsonObject capabilities = result(call(withSkill(), "server/discover", null, null)).getJsonObject("capabilities");
        assertTrue(capabilities.containsKey("resources"));
        assertTrue(capabilities.getJsonObject("extensions").getJsonObject("io.modelcontextprotocol/skills").getBoolean("directoryRead"));
    }

    @Test
    void withoutSkillsTheMethodsDoNotExist() {
        McpRequestHandler handler = withoutSkill();
        assertFalse(result(call(handler, "server/discover", null, null)).getJsonObject("capabilities").containsKey("extensions"));
        assertEquals(ErrorCodes.METHOD_NOT_FOUND, error(call(handler, "skills/list", null, null)).getInt("code"));
        assertEquals(ErrorCodes.METHOD_NOT_FOUND, error(call(handler, "skills/get", Json.createObjectBuilder().add("uri", "skill://a/SKILL.md").build(), null)).getInt("code"));
        assertEquals(ErrorCodes.METHOD_NOT_FOUND, error(call(handler, "resources/directory/read", Json.createObjectBuilder().add("uri", "skill://a").build(), null)).getInt("code"));
    }

    // ---- list and get ----------------------------------------------------------------------------------------------

    @Test
    void listCarriesFrontmatterAndTheManifest() {
        JsonObject result = result(call(withSkill(), "skills/list", null, null));
        assertEquals("complete", result.getString("resultType"));
        assertEquals(5_000, result.getJsonNumber("ttlMs").longValue());
        assertEquals("public", result.getString("cacheScope"));
        JsonObject entry = result.getJsonArray("skills").getJsonObject(0);
        assertEquals("skill://code-review/SKILL.md", entry.getString("uri"));
        assertEquals("code-review", entry.getJsonObject("frontmatter").getString("name"));
        assertEquals(2, entry.getJsonArray("resources").size());
        JsonObject first = entry.getJsonArray("resources").getJsonObject(0);
        assertEquals("skill://code-review/SKILL.md", first.getString("uri"));
        assertTrue(first.getString("digest").startsWith("sha256:"));
        assertEquals(MAIN.getBytes(StandardCharsets.UTF_8).length, first.getInt("size"));
    }

    @Test
    void getReturnsTheSameEntryAndRefusesUnknownUris() {
        McpRequestHandler handler = withSkill();
        JsonObject skill = result(call(handler, "skills/get", Json.createObjectBuilder().add("uri", "skill://code-review/SKILL.md").build(), null)).getJsonObject("skill");
        assertEquals("skill://code-review/SKILL.md", skill.getString("uri"));
        assertEquals(ErrorCodes.INVALID_PARAMS, error(call(handler, "skills/get", Json.createObjectBuilder().add("uri", "skill://code-review/references/checklist.md").build(), null)).getInt("code"));
        assertEquals(ErrorCodes.INVALID_PARAMS, error(call(handler, "skills/get", Json.createObjectBuilder().add("uri", "skill://nope/SKILL.md").build(), null)).getInt("code"));
        assertEquals(ErrorCodes.INVALID_PARAMS, error(call(handler, "skills/get", Json.createObjectBuilder().build(), null)).getInt("code"));
    }

    // ---- reading ---------------------------------------------------------------------------------------------------

    @Test
    void filesAreReadThroughResourcesRead() {
        McpRequestHandler handler = withSkill();
        JsonObject main = result(call(handler, "resources/read", Json.createObjectBuilder().add("uri", "skill://code-review/SKILL.md").build(), "skill://code-review/SKILL.md")).getJsonArray("contents").getJsonObject(0);
        assertEquals("text/markdown", main.getString("mimeType"));
        assertEquals(MAIN, main.getString("text"));
        JsonObject support = result(call(handler, "resources/read", Json.createObjectBuilder().add("uri", "skill://code-review/references/checklist.md").build(), "skill://code-review/references/checklist.md")).getJsonArray("contents").getJsonObject(0);
        assertEquals("# Checklist\n", support.getString("text"));
    }

    @Test
    void directoriesListTheirChildren() {
        McpRequestHandler handler = withSkill();
        JsonObject root = result(call(handler, "resources/directory/read", Json.createObjectBuilder().add("uri", "skill://code-review").build(), null));
        assertEquals(2, root.getJsonArray("resources").size());
        JsonObject first = root.getJsonArray("resources").getJsonObject(0);
        assertEquals("SKILL.md", first.getString("name"));
        assertEquals("skill://code-review/SKILL.md", first.getString("uri"));
        JsonObject second = root.getJsonArray("resources").getJsonObject(1);
        assertEquals("inode/directory", second.getString("mimeType"));
        JsonObject refs = result(call(handler, "resources/directory/read", Json.createObjectBuilder().add("uri", "skill://code-review/references").build(), null));
        assertEquals("skill://code-review/references/checklist.md", refs.getJsonArray("resources").getJsonObject(0).getString("uri"));
        assertEquals(ErrorCodes.INVALID_PARAMS, error(call(handler, "resources/directory/read", Json.createObjectBuilder().add("uri", "skill://code-review/none").build(), null)).getInt("code"));
    }

    // ---- dynamic skills --------------------------------------------------------------------------------------------

    private static final String GENERATED = "---\nname: report\ndescription: Explains the report of the day.\n---\n\n# Report\n";

    private McpRequestHandler withDynamicSkill(AtomicInteger version) {
        McpSkill skill = McpSkill.dynamic("report", () -> {
            Map<String, String> files = new LinkedHashMap<>();
            files.put("SKILL.md", GENERATED);
            files.put("data/today.md", "version " + version.get() + "\n");
            return files;
        });
        return new McpRequestHandler(McpRegistry.of(List.<McpToolHandler>of(), List.of(skill)), config);
    }

    @Test
    void aDynamicSkillIsListedWithoutAManifest() {
        McpRequestHandler handler = withDynamicSkill(new AtomicInteger(1));
        JsonObject entry = result(call(handler, "skills/list", null, null)).getJsonArray("skills").getJsonObject(0);
        assertEquals("skill://report/SKILL.md", entry.getString("uri"));
        assertEquals("dynamic", entry.getString("resources"));
        assertEquals("report", entry.getJsonObject("frontmatter").getString("name"));
        JsonObject got = result(call(handler, "skills/get", Json.createObjectBuilder().add("uri", "skill://report/SKILL.md").build(), null)).getJsonObject("skill");
        assertEquals("dynamic", got.getString("resources"));
    }

    @Test
    void aDynamicSkillServesFreshContentOnEveryRead() {
        AtomicInteger version = new AtomicInteger(1);
        McpRequestHandler handler = withDynamicSkill(version);
        String uri = "skill://report/data/today.md";
        assertEquals("version 1\n", result(call(handler, "resources/read", Json.createObjectBuilder().add("uri", uri).build(), uri)).getJsonArray("contents").getJsonObject(0).getString("text"));
        version.set(2);
        assertEquals("version 2\n", result(call(handler, "resources/read", Json.createObjectBuilder().add("uri", uri).build(), uri)).getJsonArray("contents").getJsonObject(0).getString("text"));
        String main = "skill://report/SKILL.md";
        assertEquals(GENERATED, result(call(handler, "resources/read", Json.createObjectBuilder().add("uri", main).build(), main)).getJsonArray("contents").getJsonObject(0).getString("text"));
    }

    @Test
    void aDynamicSkillRefusesFilesItDoesNotHaveAndBadPaths() {
        McpRequestHandler handler = withDynamicSkill(new AtomicInteger(1));
        for (String uri : List.of("skill://report/none.md", "skill://report/../x", "skill://report/", "skill://report")) {
            assertEquals(ErrorCodes.INVALID_PARAMS, error(call(handler, "resources/read", Json.createObjectBuilder().add("uri", uri).build(), uri)).getInt("code"), uri);
        }
    }

    @Test
    void aDynamicSkillListsItsDirectoriesFromTheCurrentPaths() {
        McpRequestHandler handler = withDynamicSkill(new AtomicInteger(1));
        JsonObject root = result(call(handler, "resources/directory/read", Json.createObjectBuilder().add("uri", "skill://report").build(), null));
        assertEquals(2, root.getJsonArray("resources").size());
        assertEquals("text/markdown", root.getJsonArray("resources").getJsonObject(0).getString("mimeType"));   // SKILL.md sorts before data
        assertEquals("inode/directory", root.getJsonArray("resources").getJsonObject(1).getString("mimeType"));
        JsonObject data = result(call(handler, "resources/directory/read", Json.createObjectBuilder().add("uri", "skill://report/data").build(), null));
        assertEquals("skill://report/data/today.md", data.getJsonArray("resources").getJsonObject(0).getString("uri"));
    }

    @Test
    void aDynamicSkillThatStopsProducingAValidSkillMdIsLeftOutOfTheList() {
        java.util.concurrent.atomic.AtomicBoolean broken = new java.util.concurrent.atomic.AtomicBoolean();
        McpSkill flaky = McpSkill.dynamic("report", () -> {
            Map<String, String> files = new LinkedHashMap<>();
            files.put("SKILL.md", broken.get() ? "no frontmatter" : GENERATED);
            return files;
        });
        McpRequestHandler handler = new McpRequestHandler(McpRegistry.of(List.<McpToolHandler>of(), List.of(flaky, McpSkill.of("code-review", Map.of("SKILL.md", MAIN)))), config);
        assertEquals(2, result(call(handler, "skills/list", null, null)).getJsonArray("skills").size());
        broken.set(true);
        assertEquals(1, result(call(handler, "skills/list", null, null)).getJsonArray("skills").size());
        assertEquals(ErrorCodes.INTERNAL_ERROR, error(call(handler, "skills/get", Json.createObjectBuilder().add("uri", "skill://report/SKILL.md").build(), null)).getInt("code"));
    }

    @Test
    void aDynamicSkillIsCheckedAtStartupLikeAFixedOne() {
        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> McpRegistry.of(List.<McpToolHandler>of(), List.of(McpSkill.dynamic("report", () -> Map.of("other.md", "x"))))).getMessage().contains("SKILL.md"));
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private McpHttpResponse call(McpRequestHandler handler, String method, JsonObject extra, String name) {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.put("MCP-Protocol-Version", VERSION);
        headers.put("Mcp-Method", method);
        if (name != null) {
            headers.put("Mcp-Name", name);
        }
        JsonObjectBuilder params = extra == null ? Json.createObjectBuilder() : Json.createObjectBuilder(extra);
        JsonObject meta = Json.createObjectBuilder().add("io.modelcontextprotocol/protocolVersion", VERSION).add("io.modelcontextprotocol/clientInfo", Json.createObjectBuilder().add("name", "test-client").add("version", "0")).add("io.modelcontextprotocol/clientCapabilities", Json.createObjectBuilder()).build();
        params.add("_meta", meta);
        String body = Json.createObjectBuilder().add("jsonrpc", "2.0").add("id", 1).add("method", method).add("params", params).build().toString();
        return handler.handle(headers::get, new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static JsonObject json(McpHttpResponse response) {
        try (JsonReader reader = Json.createReader(new StringReader(response.body()))) {
            return reader.readObject();
        }
    }

    private static JsonObject result(McpHttpResponse response) {
        assertEquals(200, response.status(), response.body());
        return json(response).getJsonObject("result");
    }

    private static JsonObject error(McpHttpResponse response) {
        return json(response).getJsonObject("error");
    }
}
