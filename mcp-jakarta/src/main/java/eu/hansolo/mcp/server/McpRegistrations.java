package eu.hansolo.mcp.server;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import eu.hansolo.mcp.api.McpPromptHandler;
import eu.hansolo.mcp.api.McpToolHandler;
import eu.hansolo.mcp.auth.AuthConfig;

/**
 * Adds, replaces and removes tools and prompts while the server runs, and tells every client that holds a
 * {@code subscriptions/listen} stream to list them again. Inject it where the change happens:
 *
 * <pre>{@code
 * @Inject McpRegistrations registrations;
 * ...
 * registrations.addTool(new ReportTool(report));        // clients get notifications/tools/list_changed
 * ...
 * registrations.removeTool("report-" + report.id());
 * }</pre>
 *
 * <p>The handlers are the same objects the startup scan finds: implement {@link McpToolHandler} or {@link McpPromptHandler}. They are
 * checked as at startup (valid and unique name, schema present), and a handler that needs scopes is refused while authentication is
 * off, because the scopes could never be enforced. A call that is already running when its tool is removed finishes normally.
 *
 * <p>Tools and prompts found at startup (annotations, CDI beans) can be replaced or removed here too. Resources, templates and
 * completions are fixed at startup.
 *
 * <p><b>This is per server instance</b>: the registry of this instance changes, and only the clients listening here are told. With
 * several instances, make the same call on each of them (at startup of the instance, or from a message topic or a database
 * notification). A {@link McpNotificationBridge} does not carry registrations, on purpose: it would tell the other clients about a
 * change their instance does not have yet. Clients that cached a list ({@code ttlMs}) see the change at the latest when the cache
 * expires, or at once if they listen.
 */
@Singleton
public class McpRegistrations {

    private final McpRegistry      registry;
    private final McpNotifications notifications;
    private final AuthConfig       auth;


    @Inject
    public McpRegistrations(McpRegistry registry, McpNotifications notifications, AuthConfig auth) {
        this.registry      = registry;
        this.notifications = notifications;
        this.auth          = auth;
    }


    /**
     * Adds a tool.
     *
     * @throws IllegalStateException if the name is invalid or already taken, the schema is missing, or the tool needs scopes while authentication is off
     */
    public void addTool(McpToolHandler tool) {
        checkScopes("Tool '" + tool.name() + "'", tool.requiredScopes());
        registry.addTool(tool, false);
        notifications.toolsListChangedLocally();
    }

    /** Adds the tool, or replaces the one with the same name (a new description or schema, for example). */
    public void replaceTool(McpToolHandler tool) {
        checkScopes("Tool '" + tool.name() + "'", tool.requiredScopes());
        registry.addTool(tool, true);
        notifications.toolsListChangedLocally();
    }

    /** @return true if there was such a tool; clients are only told if there was */
    public boolean removeTool(String name) {
        boolean removed = registry.removeTool(name);
        if (removed) {
            notifications.toolsListChangedLocally();
        }
        return removed;
    }

    /**
     * Adds a prompt. The first prompt switches the {@code prompts} capability on if the server started without any.
     *
     * @throws IllegalStateException if the name is invalid or already taken, or the prompt needs scopes while authentication is off
     */
    public void addPrompt(McpPromptHandler prompt) {
        checkScopes("Prompt '" + prompt.name() + "'", prompt.requiredScopes());
        registry.addPrompt(prompt, false);
        notifications.promptsListChangedLocally();
    }

    /** Adds the prompt, or replaces the one with the same name. */
    public void replacePrompt(McpPromptHandler prompt) {
        checkScopes("Prompt '" + prompt.name() + "'", prompt.requiredScopes());
        registry.addPrompt(prompt, true);
        notifications.promptsListChangedLocally();
    }

    /** @return true if there was such a prompt; clients are only told if there was */
    public boolean removePrompt(String name) {
        boolean removed = registry.removePrompt(name);
        if (removed) {
            notifications.promptsListChangedLocally();
        }
        return removed;
    }

    private void checkScopes(String what, java.util.Set<String> scopes) {
        if (!auth.enabled() && !scopes.isEmpty()) {
            throw new IllegalStateException(what + " requires " + scopes + " but mcp.auth.enabled is false, so the scopes could never be enforced");
        }
    }
}
