package eu.hansolo.mcp.api;

import jakarta.json.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * An Agent Skill this server offers through the Skills extension ({@code io.modelcontextprotocol/skills}, experimental): a directory
 * with a {@code SKILL.md} and optional supporting files. Implement it in a CDI bean, or build one with {@link #of} or
 * {@link #fromClasspath}. The server checks every skill at startup, computes the digests of the manifest from the bytes returned by
 * {@link #files()}, and announces the extension in {@code server/discover} as soon as one skill exists.
 *
 * <p>Keep the content stable: the digests are computed from what {@link #files()} returns, and hosts bind their approval to them.
 * A skill whose content is generated for each request can say so with {@link #dynamic()}: it then has no digests.
 */
public interface McpSkill {

    /** The skill's name, also its directory in the URI ({@code skill://<name>/SKILL.md}). It must equal {@code name} in the frontmatter. */
    String name();

    /** The files by path relative to the skill root, for example {@code SKILL.md} and {@code references/checklist.md}. {@code SKILL.md} is required. */
    Map<String, byte[]> files();

    /**
     * True if the content of this skill is generated and may differ from one call to the next. The entry in {@code skills/list} and
     * {@code skills/get} then carries {@code "resources": "dynamic"} instead of a manifest, and no digests are computed: {@link #files()}
     * is read once at startup to check the skill, then {@link #file} and {@link #paths()} are asked on every request. The specification
     * lets hosts decline such skills, and a host that accepts one must not treat an earlier approval as covering later content, so
     * prefer a fixed skill where the content does not really change. The frontmatter in the entry is read again for every listing, so
     * keep it equal to the frontmatter of the {@code SKILL.md} that {@link #file} returns: hosts compare the two.
     */
    default boolean dynamic() {
        return false;
    }

    /**
     * One file of a {@linkplain #dynamic() dynamic} skill, or null if there is none at this path. The default looks into {@link #files()};
     * override it to generate only the file that was asked for. The path has been checked (no {@code ..}, no odd segments).
     */
    default byte[] file(String path) {
        return files().get(path);
    }

    /** The paths that exist now, for directory reads of a {@linkplain #dynamic() dynamic} skill. The default is the key set of {@link #files()}. */
    default Set<String> paths() {
        return files().keySet();
    }

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

    /**
     * A {@linkplain #dynamic() dynamic} skill from text files that are produced anew whenever the skill is read. {@code SKILL.md} must be
     * among them and begin with frontmatter whose {@code name} is {@code name}; the supplier is called once at startup to check that,
     * and again for each request.
     */
    static McpSkill dynamic(String name, Supplier<Map<String, String>> textFiles) {
        return new SimpleDynamicSkill(name, textFiles);
    }
}
