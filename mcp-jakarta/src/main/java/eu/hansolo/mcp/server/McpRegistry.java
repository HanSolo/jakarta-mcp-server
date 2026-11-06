package eu.hansolo.mcp.server;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import eu.hansolo.mcp.api.McpCompletionHandler;
import eu.hansolo.mcp.api.McpPromptHandler;
import eu.hansolo.mcp.api.McpResourceHandler;
import eu.hansolo.mcp.api.McpResourceTemplateHandler;
import eu.hansolo.mcp.api.McpToolHandler;
import eu.hansolo.mcp.api.ResourceCall;
import eu.hansolo.mcp.api.ResourceResult;
import eu.hansolo.mcp.tools.CdiAnnotationScanner;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.regex.Pattern;

/**
 * Every tool, resource, resource template and prompt of the deployment, validated and listed in a deterministic order (by
 * name or URI) so clients can cache the lists. Everything found at startup is validated once; tools and prompts can also be added
 * and removed while the server runs, through {@link McpRegistrations}, which also tells subscribed clients.
 */
@Singleton
public class McpRegistry {

    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,128}");

    private final ConcurrentNavigableMap<String, McpToolHandler> tools;
    private final SortedMap<String, McpResourceHandler> resources;
    private final SortedMap<String, TemplateEntry>      templates;
    private final ConcurrentNavigableMap<String, McpPromptHandler> prompts;
    private volatile boolean                            promptsEnabled;
    private final Map<String, McpCompletionHandler>     completions;


    @Inject
    public McpRegistry(Instance<McpToolHandler> tools, Instance<McpResourceHandler> resources, Instance<McpResourceTemplateHandler> templates, Instance<McpPromptHandler> prompts, Instance<McpCompletionHandler> completions, CdiAnnotationScanner annotated) {
        this(concat(tools, annotated.tools()), concat(resources, annotated.resources()), concat(templates, annotated.templates()), concat(prompts, annotated.prompts()), concat(completions, annotated.completions()));
    }

    private McpRegistry(Iterable<? extends McpToolHandler> tools, Iterable<? extends McpResourceHandler> resources, Iterable<? extends McpResourceTemplateHandler> templates, Iterable<? extends McpPromptHandler> prompts, Iterable<? extends McpCompletionHandler> completions) {
        this.tools     = collectTools(tools);
        this.resources = collectResources(resources);
        this.templates = collectTemplates(templates);
        this.prompts   = collectPrompts(prompts);
        this.completions = collectCompletions(completions);
    }


    /** Builds a registry with tools only, without CDI (tests, other containers). */
    public static McpRegistry of(Iterable<? extends McpToolHandler> tools) {
        return new McpRegistry(tools, List.of(), List.of(), List.of(), List.of());
    }

    /** Builds a registry with everything, without CDI. */
    public static McpRegistry of(Iterable<? extends McpToolHandler> tools, Iterable<? extends McpResourceHandler> resources, Iterable<? extends McpResourceTemplateHandler> templates, Iterable<? extends McpPromptHandler> prompts) {
        return new McpRegistry(tools, resources, templates, prompts, List.of());
    }

    /** Builds a registry with everything including completion sources, without CDI. */
    public static McpRegistry of(Iterable<? extends McpToolHandler> tools, Iterable<? extends McpResourceHandler> resources, Iterable<? extends McpResourceTemplateHandler> templates, Iterable<? extends McpPromptHandler> prompts, Iterable<? extends McpCompletionHandler> completions) {
        return new McpRegistry(tools, resources, templates, prompts, completions);
    }

    private static <T> List<T> concat(Iterable<T> first, Iterable<? extends T> second) {
        List<T> all = new ArrayList<>();
        first.forEach(all::add);
        second.forEach(all::add);
        return all;
    }

    // ---- tools -----------------------------------------------------------------------------------------------------

    public List<McpToolHandler> all() {
        return List.copyOf(tools.values());
    }

    public Optional<McpToolHandler> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    // ---- resources -------------------------------------------------------------------------------------------------

    public List<McpResourceHandler> resources() {
        return List.copyOf(resources.values());
    }

    public List<McpResourceTemplateHandler> templates() {
        return templates.values().stream().map(TemplateEntry::handler).toList();
    }

    public boolean hasResources() {
        return !resources.isEmpty() || !templates.isEmpty();
    }

    /** The resource for this URI: an exact fixed resource first, otherwise the first template (in template order) that matches. */
    public Optional<ResolvedResource> resolve(String uri) {
        McpResourceHandler fixed = resources.get(uri);
        if (fixed != null) {
            return Optional.of(new ResolvedResource(fixed.requiredScopes(), Map.of(), fixed::read));
        }
        for (TemplateEntry entry : templates.values()) {
            Optional<Map<String, String>> variables = entry.template().match(uri);
            if (variables.isPresent()) {
                return Optional.of(new ResolvedResource(entry.handler().requiredScopes(), variables.get(), entry.handler()::read));
            }
        }
        return Optional.empty();
    }

    // ---- prompts ---------------------------------------------------------------------------------------------------

    public List<McpPromptHandler> prompts() {
        return List.copyOf(prompts.values());
    }

    /** True if prompts are offered: some exist now, or the application has registered one at runtime (and may have removed it again). */
    public boolean hasPrompts() {
        return promptsEnabled || !prompts.isEmpty();
    }

    public Optional<McpPromptHandler> findPrompt(String name) {
        return Optional.ofNullable(prompts.get(name));
    }

    // ---- completions -----------------------------------------------------------------------------------------------

    public boolean hasCompletions() {
        return !completions.isEmpty();
    }

    /** What a completion request may refer to: the argument names that exist for a prompt or template, and the scopes guarding it. */
    public Optional<CompletionTarget> completionTarget(McpCompletionHandler.Kind kind, String target) {
        if (kind == McpCompletionHandler.Kind.PROMPT) {
            McpPromptHandler prompt = prompts.get(target);
            return prompt == null ? Optional.empty() : Optional.of(new CompletionTarget(prompt.arguments().stream().map(a -> a.name()).toList(), prompt.requiredScopes()));
        }
        TemplateEntry entry = templates.get(target);
        return entry == null ? Optional.empty() : Optional.of(new CompletionTarget(entry.template().variables(), entry.handler().requiredScopes()));
    }

    public Optional<McpCompletionHandler> findCompletion(McpCompletionHandler.Kind kind, String target, String argument) {
        return Optional.ofNullable(completions.get(completionKey(kind, target, argument)));
    }

    private static String completionKey(McpCompletionHandler.Kind kind, String target, String argument) {
        return kind + "\u0000" + target + "\u0000" + argument;
    }

    private Map<String, McpCompletionHandler> collectCompletions(Iterable<? extends McpCompletionHandler> source) {
        Map<String, McpCompletionHandler> result = new java.util.LinkedHashMap<>();
        for (McpCompletionHandler handler : source) {
            String what = handler.kind() + " " + handler.target() + " argument " + handler.argument();
            Optional<CompletionTarget> target = completionTarget(handler.kind(), handler.target());
            if (target.isEmpty()) {
                throw new IllegalStateException("Completion for " + what + ": no such " + (handler.kind() == McpCompletionHandler.Kind.PROMPT ? "prompt" : "resource template"));
            }
            if (!target.get().arguments().contains(handler.argument())) {
                throw new IllegalStateException("Completion for " + what + ": unknown argument, expected one of " + target.get().arguments());
            }
            if (result.put(completionKey(handler.kind(), handler.target(), handler.argument()), handler) != null) {
                throw new IllegalStateException("Duplicate completion handler for " + what);
            }
        }
        return result;
    }

    // ---- scopes ----------------------------------------------------------------------------------------------------

    /** Descriptions of the primitives that declare required scopes, for the startup check and the list cache scope. */
    public List<String> scopedPrimitives() {
        List<String> found = new ArrayList<>();
        tools.values().stream().filter(t -> !t.requiredScopes().isEmpty()).forEach(t -> found.add("Tool '" + t.name() + "' requires " + t.requiredScopes()));
        resources.values().stream().filter(r -> !r.requiredScopes().isEmpty()).forEach(r -> found.add("Resource '" + r.uri() + "' requires " + r.requiredScopes()));
        templates.values().stream().filter(t -> !t.handler().requiredScopes().isEmpty()).forEach(t -> found.add("Resource template '" + t.handler().uriTemplate() + "' requires " + t.handler().requiredScopes()));
        prompts.values().stream().filter(p -> !p.requiredScopes().isEmpty()).forEach(p -> found.add("Prompt '" + p.name() + "' requires " + p.requiredScopes()));
        completions.values().stream().filter(c -> !c.requiredScopes().isEmpty()).forEach(c -> found.add("Completion for " + c.target() + " argument " + c.argument() + " requires " + c.requiredScopes()));
        return found;
    }

    // ---- changes at runtime (used by McpRegistrations) -------------------------------------------------------------

    void addTool(McpToolHandler tool, boolean replace) {
        validateTool(tool);
        if (replace) {
            tools.put(tool.name(), tool);
        } else if (tools.putIfAbsent(tool.name(), tool) != null) {
            throw new IllegalStateException("Duplicate MCP tool name '" + tool.name() + "'");
        }
    }

    boolean removeTool(String name) {
        return name != null && tools.remove(name) != null;
    }

    void addPrompt(McpPromptHandler prompt, boolean replace) {
        validatePrompt(prompt);
        promptsEnabled = true;
        if (replace) {
            prompts.put(prompt.name(), prompt);
        } else if (prompts.putIfAbsent(prompt.name(), prompt) != null) {
            throw new IllegalStateException("Duplicate MCP prompt name '" + prompt.name() + "'");
        }
    }

    boolean removePrompt(String name) {
        return name != null && prompts.remove(name) != null;
    }

    // ---- validation ------------------------------------------------------------------------------------------------

    private static ConcurrentNavigableMap<String, McpToolHandler> collectTools(Iterable<? extends McpToolHandler> source) {
        ConcurrentNavigableMap<String, McpToolHandler> result = new ConcurrentSkipListMap<>();
        for (McpToolHandler tool : source) {
            validateTool(tool);
            if (result.putIfAbsent(tool.name(), tool) != null) {
                throw new IllegalStateException("Duplicate MCP tool name '" + tool.name() + "'");
            }
        }
        return result;
    }

    private static void validateTool(McpToolHandler tool) {
        String name = tool.name();
        checkName("tool", name, tool);
        if (tool.inputSchema() == null) {
            throw new IllegalStateException("MCP tool '" + name + "' has no inputSchema");
        }
    }

    private static ConcurrentNavigableMap<String, McpPromptHandler> collectPrompts(Iterable<? extends McpPromptHandler> source) {
        ConcurrentNavigableMap<String, McpPromptHandler> result = new ConcurrentSkipListMap<>();
        for (McpPromptHandler prompt : source) {
            validatePrompt(prompt);
            if (result.putIfAbsent(prompt.name(), prompt) != null) {
                throw new IllegalStateException("Duplicate MCP prompt name '" + prompt.name() + "'");
            }
        }
        return result;
    }

    private static void validatePrompt(McpPromptHandler prompt) {
        checkName("prompt", prompt.name(), prompt);
    }

    private static SortedMap<String, McpResourceHandler> collectResources(Iterable<? extends McpResourceHandler> source) {
        SortedMap<String, McpResourceHandler> result = new TreeMap<>();
        for (McpResourceHandler resource : source) {
            String uri = resource.uri();
            if (uri == null || !isAbsoluteUri(uri)) {
                throw new IllegalStateException("Invalid MCP resource URI '" + uri + "' on " + resource.getClass().getName() + ": an absolute URI with a scheme is required");
            }
            if (resource.name() == null || resource.name().isBlank()) {
                throw new IllegalStateException("MCP resource '" + uri + "' has no name");
            }
            if (result.putIfAbsent(uri, resource) != null) {
                throw new IllegalStateException("Duplicate MCP resource URI '" + uri + "'");
            }
        }
        return result;
    }

    private static SortedMap<String, TemplateEntry> collectTemplates(Iterable<? extends McpResourceTemplateHandler> source) {
        SortedMap<String, TemplateEntry> result = new TreeMap<>();
        for (McpResourceTemplateHandler handler : source) {
            String text = handler.uriTemplate();
            UriTemplate template;
            try {
                template = UriTemplate.compile(text == null ? "" : text);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("Invalid MCP resource template on " + handler.getClass().getName() + ": " + e.getMessage(), e);
            }
            if (handler.name() == null || handler.name().isBlank()) {
                throw new IllegalStateException("MCP resource template '" + text + "' has no name");
            }
            if (result.putIfAbsent(text, new TemplateEntry(template, handler)) != null) {
                throw new IllegalStateException("Duplicate MCP resource template '" + text + "'");
            }
        }
        return result;
    }

    private static void checkName(String kind, String name, Object owner) {
        if (name == null || !VALID_NAME.matcher(name).matches()) {
            throw new IllegalStateException("Invalid MCP " + kind + " name '" + name + "' on " + owner.getClass().getName() + ": use 1-128 characters from A-Z a-z 0-9 _ - .");
        }
    }

    static boolean isAbsoluteUri(String uri) {
        try {
            return URI.create(uri).getScheme() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }


    /** A resource found for a URI, ready to be read once the call context is known. */
    public record ResolvedResource(Set<String> requiredScopes, Map<String, String> variables, Reader reader) {

        public ResourceResult read(ResourceCall call) throws Exception {
            return reader.read(call);
        }


        @FunctionalInterface
        public interface Reader {

            ResourceResult read(ResourceCall call) throws Exception;
        }
    }


    /** The argument names a completion may ask about and the scopes needed for the prompt or template. */
    public record CompletionTarget(List<String> arguments, Set<String> requiredScopes) {
    }


    private record TemplateEntry(UriTemplate template, McpResourceTemplateHandler handler) {
    }
}
