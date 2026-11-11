package eu.hansolo.mcp.api;

import jakarta.enterprise.inject.Vetoed;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** The skill built by {@link McpSkill#dynamic}. {@code @Vetoed}: the archive uses bean discovery mode all. */
@Vetoed
record SimpleDynamicSkill(String name, Supplier<Map<String, String>> source) implements McpSkill {

    @Override
    public Map<String, byte[]> files() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        source.get().forEach((path, text) -> files.put(path, text.getBytes(StandardCharsets.UTF_8)));
        return files;
    }

    @Override
    public boolean dynamic() {
        return true;
    }
}
