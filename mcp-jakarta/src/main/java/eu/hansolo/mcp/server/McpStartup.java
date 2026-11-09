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


    void onStart(@Observes @Initialized(ApplicationScoped.class) Object event, McpRegistry registry, McpConfig config, AuthConfig auth, TaskService tasks) {
        if (!auth.enabled()) {
            if (!registry.scopedPrimitives().isEmpty()) {
                throw new IllegalStateException(registry.scopedPrimitives().get(0) + " but mcp.auth.enabled is false, so the scopes could never be enforced");
            }
            LOG.warning("MCP authentication is DISABLED: anyone who can reach the endpoint can call every tool. Set mcp.auth.enabled=true for anything beyond local use.");
        }
        if (config.listenAsync()) {
            LOG.info("subscriptions/listen streams run on " + (config.listenExecutor() != null ? "the executor " + config.listenExecutor() + " (virtual threads if it cannot be found)" : "virtual threads") + ", up to " + config.maxListeners() + " at once");
        }
        if (tasks.enabled()) {
            tasks.start();
            if (tasks.usesMemoryStore()) {
                LOG.warning("The Tasks extension is on and tasks are kept in memory: they are lost when this server stops and other instances cannot see them. Provide a TaskStore bean (see the mcp-jakarta-tasks-jdbc module) for anything beyond a single instance.");
            } else {
                LOG.info("The Tasks extension is on; tasks are kept in the task store bean of the application");
            }
        }
        LOG.info("MCP server " + config.serverName() + " " + config.serverVersion() + " ready with " + registry.all().size() + " tool(s), " + registry.resources().size() + " resource(s), " + registry.templates().size() + " template(s), " + registry.prompts().size() + " prompt(s), authentication " + (auth.enabled() ? "enabled for " + auth.resource() : "disabled"));
    }
}
