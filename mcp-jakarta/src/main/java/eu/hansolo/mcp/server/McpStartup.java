package eu.hansolo.mcp.server;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import eu.hansolo.mcp.auth.AuthConfig;

import java.util.logging.Logger;

/** Builds the registry and validates the configuration at deployment time, so mistakes fail the deployment instead of the first request. */
@ApplicationScoped
public class McpStartup {

    private static final Logger LOG = Logger.getLogger(McpStartup.class.getName());


    void onStart(@Observes @Initialized(ApplicationScoped.class) Object event, McpRegistry registry, McpConfig config, AuthConfig auth) {
        if (!auth.enabled()) {
            if (!registry.scopedPrimitives().isEmpty()) {
                throw new IllegalStateException(registry.scopedPrimitives().get(0) + " but mcp.auth.enabled is false, so the scopes could never be enforced");
            }
            LOG.warning("MCP authentication is DISABLED: anyone who can reach the endpoint can call every tool. Set mcp.auth.enabled=true for anything beyond local use.");
        }
        LOG.info("MCP server " + config.serverName() + " " + config.serverVersion() + " ready with " + registry.all().size() + " tool(s), " + registry.resources().size() + " resource(s), " + registry.templates().size() + " template(s), " + registry.prompts().size() + " prompt(s), authentication " + (auth.enabled() ? "enabled for " + auth.resource() : "disabled"));
    }
}
