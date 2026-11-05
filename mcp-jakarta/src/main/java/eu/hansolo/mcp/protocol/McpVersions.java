package eu.hansolo.mcp.protocol;

import java.util.List;

/** Protocol revisions this server speaks. Only the stateless revision is implemented. */
public final class McpVersions {

    public static final String       CURRENT   = "2026-07-28";
    public static final List<String> SUPPORTED = List.of(CURRENT);


    private McpVersions() {
    }


    public static boolean isSupported(String version) {
        return SUPPORTED.contains(version);
    }
}
