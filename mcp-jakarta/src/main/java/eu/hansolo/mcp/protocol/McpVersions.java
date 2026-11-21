package eu.hansolo.mcp.protocol;

import java.util.List;

/** Protocol revisions this server speaks. Only the stateless revision is implemented. */
public final class McpVersions {

    /** The protocol revision this server implements. */
    public static final String       CURRENT   = "2026-07-28";
    /** All revisions the server accepts; today only {@link #CURRENT}. */
    public static final List<String> SUPPORTED = List.of(CURRENT);


    private McpVersions() {
    }


    /** True if the server accepts this revision. */
    public static boolean isSupported(String version) {
        return SUPPORTED.contains(version);
    }
}
