package eu.hansolo.mcp.apps;

import jakarta.enterprise.context.ApplicationScoped;
import eu.hansolo.mcp.api.McpAppsExtension;

/** Announces the MCP Apps extension in {@code server/discover}. Without this bean (or {@code mcp.apps.enabled=true}) it is off. */
@ApplicationScoped
public class UiExtension extends McpAppsExtension {
}
