package eu.hansolo.mcp.server;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.json.JsonObject;
import eu.hansolo.mcp.api.Apps;
import eu.hansolo.mcp.api.Icon;
import eu.hansolo.mcp.api.McpCompletionHandler;
import eu.hansolo.mcp.api.McpPromptHandler;
import eu.hansolo.mcp.api.McpResourceHandler;
import eu.hansolo.mcp.api.McpResourceTemplateHandler;
import eu.hansolo.mcp.api.McpSkill;
import eu.hansolo.mcp.api.ResourceContent;
import eu.hansolo.mcp.api.McpToolHandler;
import eu.hansolo.mcp.api.Meta;
import eu.hansolo.mcp.api.ResourceCall;
import eu.hansolo.mcp.api.ResourceResult;
import eu.hansolo.mcp.tools.CdiAnnotationScanner;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    private final ConcurrentNavigableMap<String, McpToolHandler>     tools;
    private final ConcurrentNavigableMap<String, McpResourceHandler> resources;
    private final ConcurrentNavigableMap<String, TemplateEntry>      templates;
    private volatile boolean                                         resourcesEnabled;
    private final ConcurrentNavigableMap<String, McpPromptHandler>   prompts;
    private volatile boolean                                         promptsEnabled;
    private final Map<String, McpCompletionHandler>                  completions;
    private volatile boolean                                         completionsEnabled;
    private final SkillSet                                           skills;


    @Inject
    public McpRegistry(Instance<McpToolHandler> tools, Instance<McpResourceHandler> resources, Instance<McpResourceTemplateHandler> templates, Instance<McpPromptHandler> prompts, Instance<McpCompletionHandler> completions, CdiAnnotationScanner annotated, Instance<McpSkill> skills) {
        this(concat(tools, annotated.tools()), concat(resources, annotated.resources()), concat(templates, annotated.templates()), concat(prompts, annotated.prompts()), concat(completions, annotated.completions()), skills);
    }

    private McpRegistry(Iterable<? extends McpToolHandler> tools, Iterable<? extends McpResourceHandler> resources, Iterable<? extends McpResourceTemplateHandler> templates, Iterable<? extends McpPromptHandler> prompts, Iterable<? extends McpCompletionHandler> completions, Iterable<? extends McpSkill> skills) {
        this.tools     = collectTools(tools);
        this.resources = collectResources(resources);
        this.templates = collectTemplates(templates);
        this.prompts   = collectPrompts(prompts);
        this.completions = collectCompletions(completions);
        this.skills      = new SkillSet(skills);
        this.tools.values().forEach(this::checkAppBinding);
    }


    /** Builds a registry with tools only, without CDI (tests, other containers). */
    public static McpRegistry of(Iterable<? extends McpToolHandler> tools) {
        return new McpRegistry(tools, List.of(), List.of(), List.of(), List.of(), List.of());
    }

    /** Builds a registry with tools and skills only, without CDI (tests, other containers). */
    public static McpRegistry of(Iterable<? extends McpToolHandler> tools, Iterable<? extends McpSkill> skills) {
        return new McpRegistry(tools, List.of(), List.of(), List.of(), List.of(), skills);
    }

    /** Builds a registry with everything, without CDI. */
    public static McpRegistry of(Iterable<? extends McpToolHandler> tools, Iterable<? extends McpResourceHandler> resources, Iterable<? extends McpResourceTemplateHandler> templates, Iterable<? extends McpPromptHandler> prompts) {
        return new McpRegistry(tools, resources, templates, prompts, List.of(), List.of());
    }

    /** Builds a registry with everything including completion sources, without CDI. */
    public static McpRegistry of(Iterable<? extends McpToolHandler> tools, Iterable<? extends McpResourceHandler> resources, Iterable<? extends McpResourceTemplateHandler> templates, Iterable<? extends McpPromptHandler> prompts, Iterable<? extends McpCompletionHandler> completions) {
        return new McpRegistry(tools, resources, templates, prompts, completions, List.of());
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

    /** True if resources are offered: some exist now, or the application has registered one at runtime (and may have removed it again). */
    public boolean hasResources() {
        return resourcesEnabled || !resources.isEmpty() || !templates.isEmpty() || !skills.isEmpty();
    }

    /** The resource for this URI: an exact fixed resource first, otherwise the first template (in template order) that matches. */
    public Optional<ResolvedResource> resolve(String uri) {
        McpResourceHandler fixed = resources.get(uri);
        if (fixed != null) {
            return Optional.of(new ResolvedResource(fixed.requiredScopes(), Map.of(), fixed::read));
        }
        var skillFile = skills.file(uri);
        if (skillFile.isPresent()) {
            SkillSet.Located located = skillFile.get();
            return Optional.of(new ResolvedResource(located.skill().requiredScopes(), Map.of(), call -> ResourceResult.of(skillContent(located.file()))));
        }
        for (TemplateEntry entry : templates.values()) {
            Optional<Map<String, String>> variables = entry.template().match(uri);
            if (variables.isPresent()) {
                return Optional.of(new ResolvedResource(entry.handler().requiredScopes(), variables.get(), entry.handler()::read));
            }
        }
        return Optional.empty();
    }

    // ---- skills ----------------------------------------------------------------------------------------------------

    /** True if the deployment offers skills (the Skills extension is then announced). */
    public boolean hasSkills() {
        return !skills.isEmpty();
    }

    SkillSet skills() {
        return skills;
    }

    private static ResourceContent skillContent(SkillSet.File file) {
        return SkillSet.isText(file.mimeType()) ? ResourceContent.text(file.uri(), file.mimeType(), new String(file.bytes(), java.nio.charset.StandardCharsets.UTF_8)) : ResourceContent.blob(file.uri(), file.mimeType(), file.bytes());
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

    /** True if completions are offered: some exist now, or the application has registered one at runtime (and may have removed it again). */
    public boolean hasCompletions() {
        return completionsEnabled || !completions.isEmpty();
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
        Map<String, McpCompletionHandler> result = new java.util.concurrent.ConcurrentHashMap<>();
        for (McpCompletionHandler handler : source) {
            validateCompletion(handler);
            if (result.putIfAbsent(completionKey(handler.kind(), handler.target(), handler.argument()), handler) != null) {
                throw new IllegalStateException("Duplicate completion handler for " + describe(handler));
            }
        }
        return result;
    }

    private static String describe(McpCompletionHandler handler) {
        return handler.kind() + " " + handler.target() + " argument " + handler.argument();
    }

    /** The target must exist and know the argument, as at startup. */
    private void validateCompletion(McpCompletionHandler handler) {
        Optional<CompletionTarget> target = completionTarget(handler.kind(), handler.target());
        if (target.isEmpty()) {
            throw new IllegalStateException("Completion for " + describe(handler) + ": no such " + (handler.kind() == McpCompletionHandler.Kind.PROMPT ? "prompt" : "resource template"));
        }
        if (!target.get().arguments().contains(handler.argument())) {
            throw new IllegalStateException("Completion for " + describe(handler) + ": unknown argument, expected one of " + target.get().arguments());
        }
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
        skills.list().stream().filter(k -> !k.requiredScopes().isEmpty()).forEach(k -> found.add("Skill '" + k.name() + "' requires " + k.requiredScopes()));
        return found;
    }

    // ---- changes at runtime (used by McpRegistrations) -------------------------------------------------------------

    void addTool(McpToolHandler tool, boolean replace) {
        validateTool(tool);
        checkAppBinding(tool);
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
        boolean removed = name != null && prompts.remove(name) != null;
        if (removed) {
            dropCompletions(McpCompletionHandler.Kind.PROMPT, name);
        }
        return removed;
    }

    void addResource(McpResourceHandler resource, boolean replace) {
        validateResource(resource);
        resourcesEnabled = true;
        if (replace) {
            resources.put(resource.uri(), resource);
        } else if (resources.putIfAbsent(resource.uri(), resource) != null) {
            throw new IllegalStateException("Duplicate MCP resource URI '" + resource.uri() + "'");
        }
    }

    /**
     * @throws IllegalStateException if a tool points at this resource and no template would still serve it (the Apps specification says the referenced resource must exist)
     */
    boolean removeResource(String uri) {
        if (uri == null || !resources.containsKey(uri)) {
            return false;
        }
        checkNotBound(uri, null);
        return resources.remove(uri) != null;
    }

    /** A ui:// resource that a tool points at must stay reachable: by a fixed resource, or by a template other than {@code leaving}. */
    private void checkNotBound(String uri, TemplateEntry leaving) {
        boolean servedByTemplate = templates.values().stream().anyMatch(entry -> entry != leaving && entry.template().match(uri).isPresent());
        if (servedByTemplate || leaving != null && resources.containsKey(uri)) {
            return;
        }
        for (McpToolHandler tool : tools.values()) {
            if (Apps.boundResource(tool.meta()).filter(uri::equals).isPresent()) {
                throw new IllegalStateException("Resource '" + uri + "' cannot be removed: tool '" + tool.name() + "' points at it and nothing else serves it");
            }
        }
    }

    void addTemplate(McpResourceTemplateHandler handler, boolean replace) {
        TemplateEntry entry = compileTemplate(handler);
        resourcesEnabled = true;
        if (replace) {
            templates.put(handler.uriTemplate(), entry);
        } else if (templates.putIfAbsent(handler.uriTemplate(), entry) != null) {
            throw new IllegalStateException("Duplicate MCP resource template '" + handler.uriTemplate() + "'");
        }
    }

    /**
     * @throws IllegalStateException if a tool points at a ui:// resource that only this template serves
     */
    boolean removeTemplate(String uriTemplate) {
        TemplateEntry leaving = uriTemplate == null ? null : templates.get(uriTemplate);
        if (leaving != null) {
            for (McpToolHandler tool : tools.values()) {
                Apps.boundResource(tool.meta()).filter(uri -> leaving.template().match(uri).isPresent()).ifPresent(uri -> checkNotBound(uri, leaving));
            }
        }
        boolean removed = uriTemplate != null && templates.remove(uriTemplate) != null;
        if (removed) {
            dropCompletions(McpCompletionHandler.Kind.RESOURCE_TEMPLATE, uriTemplate);
        }
        return removed;
    }

    /** Completions of a prompt or template that is gone would never be asked for, and would come back to life if the name is used again. */
    private void dropCompletions(McpCompletionHandler.Kind kind, String target) {
        completions.values().removeIf(handler -> handler.kind() == kind && handler.target().equals(target));
    }

    void addCompletion(McpCompletionHandler handler, boolean replace) {
        validateCompletion(handler);
        completionsEnabled = true;
        String key = completionKey(handler.kind(), handler.target(), handler.argument());
        if (replace) {
            completions.put(key, handler);
        } else if (completions.putIfAbsent(key, handler) != null) {
            throw new IllegalStateException("Duplicate completion handler for " + describe(handler));
        }
    }

    boolean removeCompletion(McpCompletionHandler.Kind kind, String target, String argument) {
        return kind != null && target != null && argument != null && completions.remove(completionKey(kind, target, argument)) != null;
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
        checkDescriptors("tool '" + name + "'", tool.icons(), tool.meta());
        try {
            ParamHeaders.declared(tool.inputSchema());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("MCP tool '" + name + "': " + e.getMessage(), e);
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
        checkDescriptors("prompt '" + prompt.name() + "'", prompt.icons(), prompt.meta());
    }

    private static ConcurrentNavigableMap<String, McpResourceHandler> collectResources(Iterable<? extends McpResourceHandler> source) {
        ConcurrentNavigableMap<String, McpResourceHandler> result = new ConcurrentSkipListMap<>();
        for (McpResourceHandler resource : source) {
            validateResource(resource);
            if (result.putIfAbsent(resource.uri(), resource) != null) {
                throw new IllegalStateException("Duplicate MCP resource URI '" + resource.uri() + "'");
            }
        }
        return result;
    }

    private static void validateResource(McpResourceHandler resource) {
        String uri = resource.uri();
        if (uri == null || !isAbsoluteUri(uri)) {
            throw new IllegalStateException("Invalid MCP resource URI '" + uri + "' on " + resource.getClass().getName() + ": an absolute URI with a scheme is required");
        }
        if (resource.name() == null || resource.name().isBlank()) {
            throw new IllegalStateException("MCP resource '" + uri + "' has no name");
        }
        checkDescriptors("resource '" + uri + "'", resource.icons(), resource.meta());
    }

    private static ConcurrentNavigableMap<String, TemplateEntry> collectTemplates(Iterable<? extends McpResourceTemplateHandler> source) {
        ConcurrentNavigableMap<String, TemplateEntry> result = new ConcurrentSkipListMap<>();
        for (McpResourceTemplateHandler handler : source) {
            TemplateEntry entry = compileTemplate(handler);
            if (result.putIfAbsent(handler.uriTemplate(), entry) != null) {
                throw new IllegalStateException("Duplicate MCP resource template '" + handler.uriTemplate() + "'");
            }
        }
        return result;
    }

    private static TemplateEntry compileTemplate(McpResourceTemplateHandler handler) {
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
        checkDescriptors("resource template '" + text + "'", handler.icons(), handler.meta());
        return new TemplateEntry(template, handler);
    }

    /** A tool that names a ui:// page must have it: the Apps specification says the referenced resource must exist. */
    private void checkAppBinding(McpToolHandler tool) {
        try {
            Apps.validateToolMeta(tool.meta(), "MCP tool '" + tool.name() + "'");
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
        Apps.boundResource(tool.meta()).ifPresent(uri -> {
            if (resolve(uri).isEmpty()) {
                throw new IllegalStateException("MCP tool '" + tool.name() + "' points at " + uri + " but this server has no such resource");
            }
        });
    }

    /** Icons and _meta are optional, but a handler that returns null for the list or a bad _meta key fails at startup, not on the first listing. */
    private static void checkDescriptors(String what, List<Icon> icons, JsonObject meta) {
        if (icons == null) {
            throw new IllegalStateException("MCP " + what + ": icons() must return a list, not null (use List.of())");
        }
        try {
            Meta.validate(meta, "MCP " + what);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
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
