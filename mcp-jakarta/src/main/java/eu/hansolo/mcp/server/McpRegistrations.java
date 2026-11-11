package eu.hansolo.mcp.server;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import eu.hansolo.mcp.api.McpCompletionHandler;
import eu.hansolo.mcp.api.McpPromptHandler;
import eu.hansolo.mcp.api.McpResourceHandler;
import eu.hansolo.mcp.api.McpResourceTemplateHandler;
import eu.hansolo.mcp.api.McpToolHandler;
import eu.hansolo.mcp.auth.AuthConfig;

/**
 * Adds, replaces and removes tools, prompts, resources, resource templates and completions while the server runs, and tells every client that holds a
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
 * <p>The handlers are the same objects the startup scan finds: implement {@link McpToolHandler}, {@link McpPromptHandler},
 * {@link McpResourceHandler}, {@link McpResourceTemplateHandler} or {@link McpCompletionHandler}. They are
 * checked as at startup (valid and unique name, schema present), and a handler that needs scopes is refused while authentication is
 * off, because the scopes could never be enforced. A call that is already running when its tool is removed finishes normally.
 *
 * <p>Everything found at startup (annotations, CDI beans) can be replaced or removed here too. Resources and templates tell
 * clients with {@code notifications/resources/list_changed}; completions have no list, so nobody is told. A completion handler is
 * checked like at startup (its prompt or template must exist and know the argument), and removing a prompt or template drops its
 * completions. A resource that a tool points at with {@code _meta.ui.resourceUri} cannot be removed while that tool exists.
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

    /**
     * Adds a resource. The first resource switches the {@code resources} capability on if the server started without any.
     *
     * @throws IllegalStateException if the URI is invalid or already taken, or the resource needs scopes while authentication is off
     */
    public void addResource(McpResourceHandler resource) {
        checkScopes("Resource '" + resource.uri() + "'", resource.requiredScopes());
        registry.addResource(resource, false);
        notifications.resourcesListChangedLocally();
    }

    /** Adds the resource, or replaces the one with the same URI. */
    public void replaceResource(McpResourceHandler resource) {
        checkScopes("Resource '" + resource.uri() + "'", resource.requiredScopes());
        registry.addResource(resource, true);
        notifications.resourcesListChangedLocally();
    }

    /**
     * @return true if there was such a resource; clients are only told if there was
     * @throws IllegalStateException if a tool points at the resource
     */
    public boolean removeResource(String uri) {
        boolean removed = registry.removeResource(uri);
        if (removed) {
            notifications.resourcesListChangedLocally();
        }
        return removed;
    }

    /**
     * Adds a resource template.
     *
     * @throws IllegalStateException if the template is invalid or already taken, or it needs scopes while authentication is off
     */
    public void addResourceTemplate(McpResourceTemplateHandler template) {
        checkScopes("Resource template '" + template.uriTemplate() + "'", template.requiredScopes());
        registry.addTemplate(template, false);
        notifications.resourcesListChangedLocally();
    }

    /** Adds the template, or replaces the one with the same URI template. */
    public void replaceResourceTemplate(McpResourceTemplateHandler template) {
        checkScopes("Resource template '" + template.uriTemplate() + "'", template.requiredScopes());
        registry.addTemplate(template, true);
        notifications.resourcesListChangedLocally();
    }

    /** @return true if there was such a template; clients are only told if there was. Its completions are removed with it. */
    public boolean removeResourceTemplate(String uriTemplate) {
        boolean removed = registry.removeTemplate(uriTemplate);
        if (removed) {
            notifications.resourcesListChangedLocally();
        }
        return removed;
    }

    /**
     * Adds a completion for an argument of a prompt or resource template. The first one switches the {@code completions}
     * capability on if the server started without any.
     *
     * @throws IllegalStateException if the prompt or template does not exist or has no such argument, or one is already registered for it
     */
    public void addCompletion(McpCompletionHandler completion) {
        registry.addCompletion(completion, false);
    }

    /** Adds the completion, or replaces the one for the same argument. */
    public void replaceCompletion(McpCompletionHandler completion) {
        registry.addCompletion(completion, true);
    }

    /** @return true if there was such a completion */
    public boolean removeCompletion(McpCompletionHandler.Kind kind, String target, String argument) {
        return registry.removeCompletion(kind, target, argument);
    }

    private void checkScopes(String what, java.util.Set<String> scopes) {
        if (!auth.enabled() && !scopes.isEmpty()) {
            throw new IllegalStateException(what + " requires " + scopes + " but mcp.auth.enabled is false, so the scopes could never be enforced");
        }
    }
}
