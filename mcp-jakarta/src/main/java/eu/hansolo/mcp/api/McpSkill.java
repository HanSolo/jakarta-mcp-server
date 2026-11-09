package eu.hansolo.mcp.api;

import jakarta.json.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * An Agent Skill this server offers through the Skills extension ({@code io.modelcontextprotocol/skills}, experimental): a directory
 * with a {@code SKILL.md} and optional supporting files. Implement it in a CDI bean, or build one with {@link #of} or
 * {@link #fromClasspath}. The server checks every skill at startup, computes the digests of the manifest from the bytes returned by
 * {@link #files()}, and announces the extension in {@code server/discover} as soon as one skill exists.
 *
 * <p>Keep the content stable: the digests are computed from what {@link #files()} returns, and hosts bind their approval to them.
 */
public interface McpSkill {

    /** The skill's name, also its directory in the URI ({@code skill://<name>/SKILL.md}). It must equal {@code name} in the frontmatter. */
    String name();

    /** The files by path relative to the skill root, for example {@code SKILL.md} and {@code references/checklist.md}. {@code SKILL.md} is required. */
    Map<String, byte[]> files();

    /**
     * All frontmatter fields as JSON, or null to have the server read them from {@code SKILL.md}. The server's reader understands the
     * flat subset of YAML (scalars, quoted strings, {@code >} and {@code |} blocks); override this for nested values such as
     * {@code metadata:}. Hosts compare these fields with what they parse from the file, so they must say the same.
     */
    default JsonObject frontmatter() {
        return null;
    }

    /** OAuth scopes the caller needs to list, get and read this skill. Requires {@code mcp.auth.enabled=true} when non-empty. */
    default Set<String> requiredScopes() {
        return Set.of();
    }


    /** A skill from text files held in memory. */
    static McpSkill of(String name, Map<String, String> textFiles) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        textFiles.forEach((path, text) -> files.put(path, text.getBytes(StandardCharsets.UTF_8)));
        return new SimpleSkill(name, Map.copyOf(files));
    }

    /**
     * A skill from files in the classpath (for example the WAR): each name in {@code files} is read from {@code base + "/" + name}.
     * A classpath cannot be listed reliably across servers, so the files are named. Fails if one is missing.
     */
    static McpSkill fromClasspath(ClassLoader loader, String name, String base, String... files) {
        Map<String, byte[]> content = new LinkedHashMap<>();
        for (String file : files) {
            String resource = base.endsWith("/") ? base + file : base + "/" + file;
            try (InputStream in = loader.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IllegalArgumentException("Skill '" + name + "': no classpath resource " + resource);
                }
                content.put(file, in.readAllBytes());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return new SimpleSkill(name, Map.copyOf(content));
    }
}
