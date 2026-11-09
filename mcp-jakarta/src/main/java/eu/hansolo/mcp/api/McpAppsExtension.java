package eu.hansolo.mcp.api;

import jakarta.enterprise.inject.Vetoed;

/**
 * Announces the MCP Apps extension. The library does this by itself when {@code mcp.apps.enabled=true}; this class is for code that
 * wants to register it as a bean instead (subclass it and add a CDI scope).
 *
 * <p>This class is {@code @Vetoed}: the library archive uses bean discovery mode {@code all}, so without it the container would also
 * register this class itself, and the subclass would announce the extension twice ("Duplicate MCP extension"). {@code @Vetoed} is not
 * inherited, so the subclass is still a bean.
 */
@Vetoed
public class McpAppsExtension implements McpExtension {

    @Override
    public String id() {
        return Apps.EXTENSION_ID;
    }
}
