package eu.hansolo.mcp.itfixture;

import eu.hansolo.mcp.api.McpSkill;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import java.util.LinkedHashMap;
import java.util.Map;

/** Two skills for the integration tests: one held in memory, one read from the WAR's classpath. */
@ApplicationScoped
public class FixtureSkills {

    @Produces
    McpSkill codeReview() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("SKILL.md", "---\nname: code-review\ndescription: Review code using the team's checklist.\n---\n\n# Code review\n\nRead `references/checklist.md`, then review the diff.\n");
        files.put("references/checklist.md", "# Review checklist\n\nCheck correctness, tests, and compatibility.\n");
        return McpSkill.of("code-review", files);
    }

    @Produces
    McpSkill greeter() {
        return McpSkill.fromClasspath(FixtureSkills.class.getClassLoader(), "greeter", "skills/greeter", "SKILL.md", "references/tone.md");
    }
}
