package eu.hansolo.mcp.server;

import eu.hansolo.mcp.api.McpSkill;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillSetTest {

    private static final String MAIN = "---\nname: code-review\ndescription: Review code using the team's checklist.\n---\n\n# Code review\n";

    private static McpSkill skill() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("SKILL.md", MAIN);
        files.put("references/checklist.md", "# Review checklist\n");
        files.put("scripts/run.sh", "echo hi\n");
        return McpSkill.of("code-review", files);
    }

    private static String sha256(String text) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    // ---- manifest --------------------------------------------------------------------------------------------------

    @Test
    void theManifestListsSkillMdFirstWithDigestAndSize() throws Exception {
        SkillSet set = new SkillSet(List.of(skill()));
        SkillSet.Skill found = set.get("skill://code-review/SKILL.md").orElseThrow();
        assertEquals("code-review", found.frontmatter().getString("name"));
        assertEquals(3, found.files().size());
        assertEquals("skill://code-review/SKILL.md", found.files().get(0).uri());
        assertEquals(sha256(MAIN), found.files().get(0).digest());
        assertEquals(MAIN.getBytes(StandardCharsets.UTF_8).length, found.files().get(0).bytes().length);
        assertEquals("skill://code-review/references/checklist.md", found.files().get(1).uri());
        assertEquals("text/markdown", found.files().get(1).mimeType());
    }

    @Test
    void filesAreFoundByUriAndUnknownOnesAreNot() {
        SkillSet set = new SkillSet(List.of(skill()));
        assertTrue(set.file("skill://code-review/references/checklist.md").isPresent());
        assertFalse(set.file("skill://code-review/references/missing.md").isPresent());
        assertFalse(set.file("skill://other/SKILL.md").isPresent());
        assertFalse(set.get("skill://code-review/references/checklist.md").isPresent());
    }

    @Test
    void directoriesListDirectChildrenOnly() {
        SkillSet set = new SkillSet(List.of(skill()));
        SkillSet.Directory root = set.directory("skill://code-review").orElseThrow();
        assertEquals("inode/directory", root.children().get("references"));
        assertEquals("inode/directory", root.children().get("scripts"));
        assertEquals("text/markdown", root.children().get("SKILL.md"));
        assertEquals(3, root.children().size());
        SkillSet.Directory refs = set.directory("skill://code-review/references").orElseThrow();
        assertEquals(List.of("checklist.md"), List.copyOf(refs.children().keySet()));
        assertFalse(set.directory("skill://code-review/nothing").isPresent());
        assertFalse(set.directory("skill://code-review/references/").isPresent());
        assertFalse(set.directory("skill://nope").isPresent());
    }

    @Test
    void binaryFilesAreNotText() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("SKILL.md", MAIN.getBytes(StandardCharsets.UTF_8));
        files.put("assets/logo.bin", new byte[] { (byte) 0xff, (byte) 0xfe, 0 });
        McpSkill binary = new McpSkill() {
            public String name() { return "code-review"; }
            public Map<String, byte[]> files() { return files; }
        };
        SkillSet.File file = new SkillSet(List.of(binary)).file("skill://code-review/assets/logo.bin").orElseThrow().read().orElseThrow();
        assertEquals("application/octet-stream", file.mimeType());
        assertFalse(SkillSet.isText(file.mimeType()));
    }

    // ---- refusals --------------------------------------------------------------------------------------------------

    @Test
    void refusesWhatTheSpecificationRefuses() {
        assertThrows(IllegalStateException.class, () -> new SkillSet(List.of(McpSkill.of("code-review", Map.of("other.md", "x")))));
        assertThrows(IllegalStateException.class, () -> new SkillSet(List.of(McpSkill.of("code-review", Map.of("SKILL.md", "no frontmatter")))));
        assertThrows(IllegalStateException.class, () -> new SkillSet(List.of(McpSkill.of("other", Map.of("SKILL.md", MAIN)))));
        assertThrows(IllegalStateException.class, () -> new SkillSet(List.of(McpSkill.of("code-review", Map.of("SKILL.md", "---\nname: code-review\n---\n")))));
        assertThrows(IllegalStateException.class, () -> new SkillSet(List.of(McpSkill.of("bad name", Map.of("SKILL.md", MAIN)))));
        assertThrows(IllegalStateException.class, () -> new SkillSet(List.of(skill(), skill())));
    }

    @Test
    void refusesUnsafePaths() {
        for (String path : new String[] { "../x.md", "a/../b.md", "/abs.md", "a//b.md", "dir/", "sp ace.md", "a\\b.md" }) {
            Map<String, String> files = new LinkedHashMap<>();
            files.put("SKILL.md", MAIN);
            files.put(path, "x");
            assertThrows(IllegalStateException.class, () -> new SkillSet(List.of(McpSkill.of("code-review", files))));
        }
    }

    // ---- frontmatter -----------------------------------------------------------------------------------------------

    @Test
    void readsScalarsQuotesAndBlocks() {
        String text = "---\nname: demo\ndescription: >\n  First line\n  second line\nlicense: 'MIT'\nversion: 3\nenabled: true\nnote: \"a \\\"b\\\" c\"\nempty:\nplain: value # comment\n---\nbody";
        JsonObject f = SkillSet.parseFrontmatter(text);
        assertEquals("First line second line\n", f.getString("description"));
        assertEquals("MIT", f.getString("license"));
        assertEquals(3, f.getInt("version"));
        assertTrue(f.getBoolean("enabled"));
        assertEquals("a \"b\" c", f.getString("note"));
        assertEquals(jakarta.json.JsonValue.NULL, f.get("empty"));
        assertEquals("value", f.getString("plain"));
    }

    @Test
    void literalBlocksKeepLines() {
        JsonObject f = SkillSet.parseFrontmatter("---\nname: demo\ndescription: |-\n  one\n  two\n---\n");
        assertEquals("one\ntwo", f.getString("description"));
    }

    @Test
    void nestedYamlNeedsAnExplicitFrontmatter() {
        assertThrows(IllegalArgumentException.class, () -> SkillSet.parseFrontmatter("---\nname: demo\nmetadata:\n  a: b\n---\n"));
        assertThrows(IllegalArgumentException.class, () -> SkillSet.parseFrontmatter("---\nname: demo\ntools: [a, b]\n---\n"));
        assertThrows(IllegalArgumentException.class, () -> SkillSet.parseFrontmatter("---\nname: demo\nname: again\n---\n"));
        assertThrows(IllegalArgumentException.class, () -> SkillSet.parseFrontmatter("---\nname: demo\n"));
    }

    @Test
    void anExplicitFrontmatterReplacesTheReader() {
        JsonObject frontmatter = jakarta.json.Json.createObjectBuilder().add("name", "code-review").add("description", "d").add("metadata", jakarta.json.Json.createObjectBuilder().add("a", "b")).build();
        McpSkill nested = new McpSkill() {
            public String name() { return "code-review"; }
            public Map<String, byte[]> files() { return Map.of("SKILL.md", "---\nname: code-review\ndescription: d\nmetadata:\n  a: b\n---\n".getBytes(StandardCharsets.UTF_8)); }
            public JsonObject frontmatter() { return frontmatter; }
        };
        assertEquals("b", new SkillSet(List.of(nested)).list().get(0).frontmatter().getJsonObject("metadata").getString("a"));
    }
}
