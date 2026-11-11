package eu.hansolo.mcp.server;

import eu.hansolo.mcp.api.McpSkill;
import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonValue;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * The skills of the deployment, checked once: names, paths, {@code SKILL.md} frontmatter, and the manifest (URI, SHA-256 digest and
 * size of every file) computed from the bytes the skill returned.
 */
final class SkillSet {

    static final String EXTENSION_ID = "io.modelcontextprotocol/skills";
    static final String SCHEME       = "skill://";
    static final String MAIN_FILE    = "SKILL.md";

    private static final Logger  LOG        = Logger.getLogger(SkillSet.class.getName());
    private static final Pattern NAME       = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern SEGMENT    = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern INTEGER    = Pattern.compile("-?(0|[1-9][0-9]*)");
    private static final Pattern KEY        = Pattern.compile("[A-Za-z0-9_-]+");
    private static final int     MAX_FILES  = 512;
    private static final long    MAX_BYTES  = 16L * 1024 * 1024;

    private final Map<String, Skill> byName = new TreeMap<>();
    private final Map<String, Skill> byUri  = new LinkedHashMap<>();
    private final Map<String, File>  files  = new LinkedHashMap<>();
    private final Map<String, Skill> owners = new LinkedHashMap<>();


    /** A file of a skill. */
    record File(String path, String uri, byte[] bytes, String mimeType, String digest) {

        JsonObject manifestEntry() {
            return Json.createObjectBuilder().add("uri", uri).add("digest", digest).add("size", bytes.length).build();
        }
    }

    /**
     * A checked skill. A dynamic skill has no files here: its frontmatter and content are asked of the source for each request, and its
     * entry says {@code "resources": "dynamic"}.
     */
    record Skill(McpSkill source, String name, String uri, JsonObject frontmatter, List<File> files, boolean dynamic) {

        Set<String> requiredScopes() {
            return source.requiredScopes();
        }

        /**
         * @throws IllegalStateException if a dynamic skill no longer produces a valid {@code SKILL.md}
         */
        JsonObject toJson() {
            if (dynamic) {
                return Json.createObjectBuilder().add("uri", uri).add("frontmatter", liveFrontmatter(this)).add("resources", "dynamic").build();
            }
            JsonArrayBuilder manifest = Json.createArrayBuilder();
            files.forEach(file -> manifest.add(file.manifestEntry()));
            return Json.createObjectBuilder().add("uri", uri).add("frontmatter", frontmatter).add("resources", manifest).build();
        }
    }


    static SkillSet none() {
        return new SkillSet(List.of());
    }

    SkillSet(Iterable<? extends McpSkill> skills) {
        for (McpSkill source : skills) {
            Skill skill = check(source);
            if (byName.putIfAbsent(skill.name(), skill) != null) {
                throw new IllegalStateException("Duplicate MCP skill '" + skill.name() + "'");
            }
            byUri.put(skill.uri(), skill);
            skill.files().forEach(file -> {
                files.put(file.uri(), file);
                owners.put(file.uri(), skill);
            });
        }
    }


    boolean isEmpty() {
        return byName.isEmpty();
    }

    /** The skills in name order. */
    List<Skill> list() {
        return List.copyOf(byName.values());
    }

    Optional<Skill> get(String uri) {
        return Optional.ofNullable(byUri.get(uri));
    }

    /**
     * A file of a skill and the skill it belongs to. The bytes of a dynamic skill are only produced by {@link #read()}, so that the
     * caller's scopes can be checked first.
     */
    record Located(Skill skill, String uri, String path, File fixed) {

        /** @return the file, or empty if a dynamic skill has no file at this path (any more) */
        Optional<File> read() {
            if (fixed != null) {
                return Optional.of(fixed);
            }
            byte[] bytes = skill.source().file(path);
            return bytes == null ? Optional.empty() : Optional.of(new File(path, uri, bytes, mimeType(path, bytes), "sha256:" + sha256(bytes)));
        }
    }

    /** The file at this URI if it belongs to a skill: a known file of a fixed skill, any well-formed path below a dynamic skill. */
    Optional<Located> file(String uri) {
        File file = files.get(uri);
        if (file != null) {
            return Optional.of(new Located(owners.get(uri), uri, file.path(), file));
        }
        if (!uri.startsWith(SCHEME)) {
            return Optional.empty();
        }
        String rest = uri.substring(SCHEME.length());
        int slash = rest.indexOf('/');
        Skill skill = slash <= 0 ? null : byName.get(rest.substring(0, slash));
        if (skill == null || !skill.dynamic()) {
            return Optional.empty();
        }
        String path = rest.substring(slash + 1);
        try {
            checkPath("MCP skill '" + skill.name() + "'", path);
        } catch (IllegalStateException e) {
            return Optional.empty();
        }
        return Optional.of(new Located(skill, uri, path, null));
    }

    /**
     * The direct children of a directory of a skill, or empty if the URI is not a directory of one. Directory URIs have no trailing
     * slash. The result is in name order.
     */
    Optional<Directory> directory(String uri) {
        if (!uri.startsWith(SCHEME) || uri.endsWith("/")) {
            return Optional.empty();
        }
        String rest = uri.substring(SCHEME.length());
        int slash = rest.indexOf('/');
        String name = slash < 0 ? rest : rest.substring(0, slash);
        Skill skill = byName.get(name);
        if (skill == null) {
            return Optional.empty();
        }
        String prefix = slash < 0 ? "" : rest.substring(slash + 1) + "/";
        Map<String, String> children = new TreeMap<>();
        Map<String, String> paths = new LinkedHashMap<>();
        if (skill.dynamic()) {
            for (String path : skill.source().paths()) {
                try {
                    checkPath("MCP skill '" + name + "'", path);
                    paths.put(path, mimeType(path, null));
                } catch (IllegalStateException e) {
                    LOG.warning("Dynamic skill '" + name + "' lists the invalid path '" + path + "'; it is left out");
                }
            }
        } else {
            skill.files().forEach(file -> paths.put(file.path(), file.mimeType()));
        }
        for (Map.Entry<String, String> file : paths.entrySet()) {
            if (!file.getKey().startsWith(prefix)) {
                continue;
            }
            String below = file.getKey().substring(prefix.length());
            int next = below.indexOf('/');
            if (next < 0) {
                children.put(below, file.getValue());
            } else {
                children.putIfAbsent(below.substring(0, next), "inode/directory");
            }
        }
        if (children.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Directory(skill, children));
    }

    record Directory(Skill skill, Map<String, String> children) {
    }


    // ---- checking --------------------------------------------------------------------------------------------------

    private static Skill check(McpSkill source) {
        String name = source.name();
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalStateException("MCP skill name '" + name + "' must match " + NAME.pattern());
        }
        String where = "MCP skill '" + name + "'";
        Map<String, byte[]> content = source.files();
        if (content == null || !content.containsKey(MAIN_FILE)) {
            throw new IllegalStateException(where + " has no " + MAIN_FILE);
        }
        if (content.size() > MAX_FILES) {
            LOG.warning(where + " has " + content.size() + " files; the specification says servers should not exceed " + MAX_FILES);
        }
        long total = 0;
        TreeSet<String> paths = new TreeSet<>(content.keySet());
        List<File> files = new ArrayList<>();
        for (String path : paths) {
            checkPath(where, path);
            byte[] bytes = content.get(path);
            if (bytes == null) {
                throw new IllegalStateException(where + ": file '" + path + "' has no content");
            }
            total += bytes.length;
            files.add(new File(path, SCHEME + name + "/" + path, bytes, mimeType(path, bytes), "sha256:" + sha256(bytes)));
        }
        if (total > MAX_BYTES) {
            LOG.warning(where + " is " + total + " bytes; the specification says servers should not exceed " + MAX_BYTES);
        }
        boolean dynamic = source.dynamic();
        // SKILL.md first, then the others in path order
        files.sort((a, b) -> a.path().equals(MAIN_FILE) ? -1 : b.path().equals(MAIN_FILE) ? 1 : a.path().compareTo(b.path()));

        JsonObject frontmatter = source.frontmatter();
        String main = new String(content.get(MAIN_FILE), StandardCharsets.UTF_8);
        if (frontmatter == null) {
            try {
                frontmatter = parseFrontmatter(main);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(where + ": " + e.getMessage(), e);
            }
        } else if (!main.startsWith("---")) {
            throw new IllegalStateException(where + ": " + MAIN_FILE + " must begin with YAML frontmatter");
        }
        if (!(frontmatter.get("name") instanceof jakarta.json.JsonString declared) || !declared.getString().equals(name)) {
            throw new IllegalStateException(where + ": frontmatter 'name' must be the skill's directory name '" + name + "'");
        }
        if (!(frontmatter.get("description") instanceof jakarta.json.JsonString description) || description.getString().isBlank()) {
            throw new IllegalStateException(where + ": frontmatter 'description' is required");
        }
        return new Skill(source, name, SCHEME + name + "/" + MAIN_FILE, frontmatter, dynamic ? List.of() : List.copyOf(files), dynamic);
    }

    /** The frontmatter of a dynamic skill as it is now, checked like at startup. */
    private static JsonObject liveFrontmatter(Skill skill) {
        String where = "MCP skill '" + skill.name() + "'";
        byte[] main = skill.source().file(MAIN_FILE);
        if (main == null) {
            throw new IllegalStateException(where + " has no " + MAIN_FILE + " any more");
        }
        JsonObject frontmatter = skill.source().frontmatter();
        if (frontmatter == null) {
            try {
                frontmatter = parseFrontmatter(new String(main, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(where + ": " + e.getMessage(), e);
            }
        }
        if (!(frontmatter.get("name") instanceof jakarta.json.JsonString declared) || !declared.getString().equals(skill.name())
            || !(frontmatter.get("description") instanceof jakarta.json.JsonString description) || description.getString().isBlank()) {
            throw new IllegalStateException(where + ": frontmatter needs 'name' equal to '" + skill.name() + "' and a 'description'");
        }
        return frontmatter;
    }

    private static void checkPath(String where, String path) {
        if (path.isEmpty() || path.startsWith("/") || path.endsWith("/")) {
            throw new IllegalStateException(where + ": invalid file path '" + path + "'");
        }
        for (String segment : path.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..") || !SEGMENT.matcher(segment).matches()) {
                throw new IllegalStateException(where + ": invalid file path '" + path + "' (use letters, digits, '.', '_' and '-' in each segment)");
            }
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    static String mimeType(String path, byte[] bytes) {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".md")) {
            return "text/markdown";
        }
        if (lower.endsWith(".json")) {
            return "application/json";
        }
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) {
            return "application/yaml";
        }
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return "text/html";
        }
        if (lower.endsWith(".csv")) {
            return "text/csv";
        }
        return bytes == null || isUtf8(bytes) ? "text/plain" : "application/octet-stream";
    }

    static boolean isText(String mimeType) {
        return !mimeType.equals("application/octet-stream");
    }

    private static boolean isUtf8(byte[] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }


    // ---- frontmatter -----------------------------------------------------------------------------------------------

    /**
     * Reads the YAML frontmatter of a {@code SKILL.md}: one {@code key: value} per line, with plain, single- or double-quoted
     * scalars, {@code true}/{@code false}/{@code null}, integers, and {@code >} / {@code |} blocks. Nested maps and lists are refused:
     * give {@link McpSkill#frontmatter()} instead.
     */
    static JsonObject parseFrontmatter(String text) {
        String[] lines = text.replace("\r\n", "\n").split("\n", -1);
        if (lines.length == 0 || !lines[0].trim().equals("---")) {
            throw new IllegalArgumentException(MAIN_FILE + " must begin with YAML frontmatter ('---' on the first line)");
        }
        JsonObjectBuilder result = Json.createObjectBuilder();
        Set<String> seen = new java.util.HashSet<>();
        int i = 1;
        boolean closed = false;
        while (i < lines.length) {
            String line = lines[i];
            if (line.trim().equals("---")) {
                closed = true;
                break;
            }
            if (line.isBlank() || line.trim().startsWith("#")) {
                i++;
                continue;
            }
            if (Character.isWhitespace(line.charAt(0)) || line.startsWith("- ")) {
                throw new IllegalArgumentException("frontmatter line " + (i + 1) + " is nested YAML, which the built-in reader does not support: override McpSkill.frontmatter()");
            }
            int colon = line.indexOf(':');
            if (colon <= 0 || (colon + 1 < line.length() && line.charAt(colon + 1) != ' ')) {
                throw new IllegalArgumentException("frontmatter line " + (i + 1) + " is not 'key: value'");
            }
            String key = line.substring(0, colon);
            if (!KEY.matcher(key).matches()) {
                throw new IllegalArgumentException("frontmatter key '" + key + "' on line " + (i + 1) + " is not supported by the built-in reader: override McpSkill.frontmatter()");
            }
            if (!seen.add(key)) {
                throw new IllegalArgumentException("frontmatter key '" + key + "' appears twice");
            }
            String value = line.substring(colon + 1).trim();
            if (value.equals(">") || value.equals("|") || value.equals(">-") || value.equals("|-")) {
                List<String> block = new ArrayList<>();
                int j = i + 1;
                while (j < lines.length && (lines[j].isBlank() || Character.isWhitespace(lines[j].charAt(0)))) {
                    block.add(lines[j]);
                    j++;
                }
                result.add(key, blockScalar(value, block));
                i = j;
                continue;
            }
            addScalar(result, key, value, i + 1);
            i++;
        }
        if (!closed) {
            throw new IllegalArgumentException("frontmatter is not closed with '---'");
        }
        return result.build();
    }

    private static String blockScalar(String style, List<String> block) {
        while (!block.isEmpty() && block.get(block.size() - 1).isBlank()) {
            block.remove(block.size() - 1);
        }
        int indent = Integer.MAX_VALUE;
        for (String line : block) {
            if (!line.isBlank()) {
                indent = Math.min(indent, line.length() - line.stripLeading().length());
            }
        }
        List<String> stripped = new ArrayList<>();
        for (String line : block) {
            stripped.add(line.isBlank() ? "" : line.substring(indent));
        }
        String body;
        if (style.startsWith("|")) {
            body = String.join("\n", stripped);
        } else {
            StringBuilder folded = new StringBuilder();
            for (int k = 0; k < stripped.size(); k++) {
                String line = stripped.get(k);
                if (line.isEmpty()) {
                    folded.append('\n');
                } else {
                    if (k > 0 && !stripped.get(k - 1).isEmpty()) {
                        folded.append(' ');
                    }
                    folded.append(line);
                }
            }
            body = folded.toString();
        }
        return style.endsWith("-") || body.isEmpty() ? body : body + "\n";
    }

    private static void addScalar(JsonObjectBuilder result, String key, String value, int lineNumber) {
        if (value.isEmpty() || value.equals("~") || value.equals("null")) {
            result.addNull(key);
        } else if (value.equals("true")) {
            result.add(key, true);
        } else if (value.equals("false")) {
            result.add(key, false);
        } else if (value.startsWith("\"")) {
            result.add(key, doubleQuoted(value, lineNumber));
        } else if (value.startsWith("'")) {
            if (value.length() < 2 || !value.endsWith("'")) {
                throw new IllegalArgumentException("frontmatter line " + lineNumber + ": unterminated quoted string");
            }
            result.add(key, value.substring(1, value.length() - 1).replace("''", "'"));
        } else if (value.startsWith("[") || value.startsWith("{") || value.startsWith("&") || value.startsWith("*") || value.startsWith("!")) {
            throw new IllegalArgumentException("frontmatter line " + lineNumber + " uses YAML the built-in reader does not support: override McpSkill.frontmatter()");
        } else {
            int comment = value.indexOf(" #");
            String plain = (comment >= 0 ? value.substring(0, comment) : value).trim();
            if (INTEGER.matcher(plain).matches() && plain.length() < 16) {
                result.add(key, Long.parseLong(plain));
            } else {
                result.add(key, plain);
            }
        }
    }

    private static String doubleQuoted(String value, int lineNumber) {
        StringBuilder out = new StringBuilder();
        int i = 1;
        while (i < value.length()) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char next = value.charAt(i + 1);
                switch (next) {
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    default -> throw new IllegalArgumentException("frontmatter line " + lineNumber + ": unsupported escape \\" + next);
                }
                i += 2;
            } else if (c == '"') {
                if (!value.substring(i + 1).trim().isEmpty() && !value.substring(i + 1).trim().startsWith("#")) {
                    throw new IllegalArgumentException("frontmatter line " + lineNumber + ": text after the closing quote");
                }
                return out.toString();
            } else {
                out.append(c);
                i++;
            }
        }
        throw new IllegalArgumentException("frontmatter line " + lineNumber + ": unterminated quoted string");
    }
}
