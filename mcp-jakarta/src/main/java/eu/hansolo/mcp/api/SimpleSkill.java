package eu.hansolo.mcp.api;

import jakarta.enterprise.inject.Vetoed;

import java.util.Map;

/** The skill built by {@link McpSkill#of} and {@link McpSkill#fromClasspath}. {@code @Vetoed}: the archive uses bean discovery mode all. */
@Vetoed
record SimpleSkill(String name, Map<String, byte[]> files) implements McpSkill {
}
