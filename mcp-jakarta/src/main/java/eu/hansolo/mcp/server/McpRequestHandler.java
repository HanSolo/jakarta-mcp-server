package eu.hansolo.mcp.server;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonException;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonString;
import jakarta.json.JsonStructure;
import jakarta.json.JsonValue;
import eu.hansolo.mcp.api.Caller;
import eu.hansolo.mcp.api.CompletionCall;
import eu.hansolo.mcp.api.CompletionResult;
import eu.hansolo.mcp.api.Icon;
import eu.hansolo.mcp.api.McpCompletionHandler;
import eu.hansolo.mcp.api.McpPromptHandler;
import eu.hansolo.mcp.api.McpResourceHandler;
import eu.hansolo.mcp.api.McpResourceTemplateHandler;
import eu.hansolo.mcp.api.McpToolHandler;
import eu.hansolo.mcp.api.PromptArgument;
import eu.hansolo.mcp.api.PromptCall;
import eu.hansolo.mcp.api.PromptMessage;
import eu.hansolo.mcp.api.ProgressReporter;
import eu.hansolo.mcp.api.PromptResult;
import eu.hansolo.mcp.api.ResourceCall;
import eu.hansolo.mcp.api.ResourceContent;
import eu.hansolo.mcp.api.RequestCancelledException;
import eu.hansolo.mcp.api.ResourceResult;
import eu.hansolo.mcp.api.Scopes;
import eu.hansolo.mcp.auth.AuthConfig;
import eu.hansolo.mcp.auth.McpAuthenticator;
import eu.hansolo.mcp.auth.TokenException;
import eu.hansolo.mcp.api.Extensions;
import eu.hansolo.mcp.api.TaskSupport;
import eu.hansolo.mcp.api.Tasks;
import eu.hansolo.mcp.api.ToolCall;
import eu.hansolo.mcp.api.ToolResult;
import eu.hansolo.mcp.api.TraceContext;
import eu.hansolo.mcp.protocol.ErrorCodes;
import eu.hansolo.mcp.protocol.JsonRpc;
import eu.hansolo.mcp.protocol.McpProtocolException;
import eu.hansolo.mcp.protocol.McpVersions;
import eu.hansolo.mcp.protocol.MetaKeys;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The whole Streamable HTTP request/response logic of MCP 2026-07-28, independent of JAX-RS: give it a header lookup and a
 * body, get back a status and JSON text. It keeps no per-client state, so any instance can serve any request.
 */
@Singleton
public class McpRequestHandler {

    private static final Logger  LOG         = Logger.getLogger(McpRequestHandler.class.getName());
    private static final Pattern ABSOLUTE_URI = Pattern.compile("[A-Za-z][A-Za-z0-9+.-]*:\\S.*", Pattern.DOTALL);
    private static final int     MAX_URI      = 2048;
    private static final int     MAX_COMPLETION_VALUES  = 100;
    private static final int     MAX_COMPLETION_VALUE   = 4096;
    private static final int     MAX_COMPLETION_CONTEXT = 64;

    private final McpRegistry     registry;
    private final McpConfig        config;
    private final AuthConfig       authConfig;
    private final McpAuthenticator authenticator;
    private final McpNotifications notifications;
    private final McpExtensions    extensions;
    private final TaskService      tasks;


    @Inject
    public McpRequestHandler(McpRegistry registry, McpConfig config, AuthConfig authConfig, McpAuthenticator authenticator, McpNotifications notifications, McpExtensions extensions, TaskService tasks) {
        this.registry      = registry;
        this.config        = config;
        this.authConfig    = authConfig;
        this.authenticator = authenticator;
        this.notifications = notifications;
        this.extensions    = extensions;
        this.tasks         = tasks;
    }

    /** A handler without the Tasks extension: a tool that returns {@code ToolResult.task(...)} runs inline. */
    public McpRequestHandler(McpRegistry registry, McpConfig config, AuthConfig authConfig, McpAuthenticator authenticator, McpNotifications notifications, McpExtensions extensions) {
        this(registry, config, authConfig, authenticator, notifications, extensions, TaskService.disabled(registry));
    }

    /** A handler that announces no extensions. */
    public McpRequestHandler(McpRegistry registry, McpConfig config, AuthConfig authConfig, McpAuthenticator authenticator, McpNotifications notifications) {
        this(registry, config, authConfig, authenticator, notifications, McpExtensions.none());
    }

    /** A handler with its own notification hub (nothing outside it can publish to the streams). */
    public McpRequestHandler(McpRegistry registry, McpConfig config, AuthConfig authConfig, McpAuthenticator authenticator) {
        this(registry, config, authConfig, authenticator, new McpNotifications(config));
    }

    /** A handler with authentication switched off. */
    public McpRequestHandler(McpRegistry registry, McpConfig config) {
        this(registry, config, AuthConfig.disabled(), header -> Caller.ANONYMOUS);
    }


    /**
     * Handles one POST to the MCP endpoint.
     *
     * @param headers case-insensitive request header lookup, returning null for absent headers
     * @param body    the request body; it is read at most {@code mcp.max-body-bytes + 1} bytes
     * @return usually a JSON body. A {@code tools/call} that carries a {@code progressToken} from a client that lists
     *         {@code text/event-stream} in {@code Accept} gets a streamed (Server-Sent Events) response instead. Everything that can
     *         fail before the tool runs (origin, authentication, headers, scopes, arguments) is still answered as an ordinary JSON
     *         error with its own HTTP status, because the status cannot change once streaming has begun.
     */
    public McpHttpResponse handle(Function<String, String> rawHeaders, InputStream body) {
        // RFC 9110 5.5: whitespace around a field value is not part of the value. Servers differ (Open Liberty keeps trailing blanks).
        Function<String, String> headers = name -> HeaderValues.trimOws(rawHeaders.apply(name));
        JsonValue id = null;
        try {
            if (!config.isOriginAllowed(headers.apply("Origin"))) {
                return new McpHttpResponse(403, JsonRpc.error(null, ErrorCodes.INVALID_REQUEST, "Origin not allowed", null).toString());
            }
            Caller caller = Caller.ANONYMOUS;
            if (authConfig.enabled()) {
                try {
                    caller = authenticator.authenticate(headers.apply("Authorization"));
                } catch (TokenException e) {
                    return unauthorized(e.getMessage());
                }
                if (!Scopes.satisfiesAll(caller.scopes(), authConfig.requiredScopes())) {
                    return forbidden("Insufficient scope", authConfig.requiredScopes());
                }
            }
            JsonObject message = parse(body);
            if (!(message.get("method") instanceof JsonString methodValue)) {
                if (message.containsKey("result") || message.containsKey("error")) {
                    throw McpProtocolException.invalidRequest("Clients must not send JSON-RPC responses");
                }
                throw McpProtocolException.invalidRequest("Missing method");
            }
            if (!(message.get("jsonrpc") instanceof JsonString version) || !version.getString().equals("2.0")) {
                throw McpProtocolException.invalidRequest("jsonrpc must be \"2.0\"");
            }
            if (!message.containsKey("id")) {
                return new McpHttpResponse(202, null);
            }
            id = requestId(message.get("id"));
            String method = methodValue.getString();
            RequestContext context = prepare(method, message, headers, caller);
            if (method.equals("subscriptions/listen")) {
                if (!HeaderValues.acceptsEventStream(headers.apply("Accept"))) {
                    throw new McpProtocolException(406, ErrorCodes.INVALID_REQUEST, "subscriptions/listen needs an Accept header that lists text/event-stream", null);
                }
                return listen(id, context);
            }
            if (method.equals("tools/call") && HeaderValues.acceptsEventStream(headers.apply("Accept"))) {
                JsonValue token = progressToken(context);
                if (token != null) {
                    PreparedTool prepared = prepareTool(context);
                    JsonValue requestId = id;
                    return McpHttpResponse.streaming(sink -> streamTool(requestId, prepared, token, sink));
                }
            }
            return new McpHttpResponse(200, JsonRpc.result(id, execute(context)).toString());
        } catch (McpProtocolException e) {
            return new McpHttpResponse(e.httpStatus(), JsonRpc.error(id, e.code(), e.getMessage(), e.data()).toString());
        } catch (AuthChallengeException e) {
            return forbidden(e.getMessage(), Set.of(e.scope().split(" ")));
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Unexpected failure while handling an MCP request", e);
            return new McpHttpResponse(500, JsonRpc.error(id, ErrorCodes.INTERNAL_ERROR, "Internal error", null).toString());
        }
    }

    /** 401 per RFC 6750 / RFC 9728: no error code when credentials were simply absent, invalid_token when they were rejected. */
    private McpHttpResponse unauthorized(String reason) {
        StringBuilder challenge = new StringBuilder("Bearer ");
        if (reason != null) {
            challenge.append("error=\"invalid_token\", error_description=\"").append(reason.replace('"', '\'')).append("\", ");
        }
        challenge.append("resource_metadata=\"").append(authConfig.metadataUrl()).append('"');
        if (!authConfig.scopesSupported().isEmpty()) {
            challenge.append(", scope=\"").append(String.join(" ", authConfig.scopesSupported())).append('"');
        }
        String message = reason == null ? "Authentication required" : "Invalid access token: " + reason;
        return new McpHttpResponse(401, JsonRpc.error(null, ErrorCodes.INVALID_REQUEST, message, null).toString(), Map.of("WWW-Authenticate", challenge.toString()));
    }

    /** 403 insufficient_scope; the challenge names the scopes needed so the client can step up. */
    private McpHttpResponse forbidden(String message, Set<String> scopes) {
        String challenge = "Bearer error=\"insufficient_scope\", scope=\"" + String.join(" ", new TreeSet<>(scopes)) + "\", resource_metadata=\"" + authConfig.metadataUrl() + "\"";
        return new McpHttpResponse(403, JsonRpc.error(null, ErrorCodes.INVALID_REQUEST, message, null).toString(), Map.of("WWW-Authenticate", challenge));
    }

    private JsonObject parse(InputStream body) {
        byte[] bytes;
        try {
            bytes = body.readNBytes(config.maxBodyBytes() + 1);
        } catch (IOException e) {
            throw McpProtocolException.invalidRequest("Could not read the request body");
        }
        if (bytes.length > config.maxBodyBytes()) {
            throw new McpProtocolException(413, ErrorCodes.INVALID_REQUEST, "Request body too large", null);
        }
        JsonStructure structure;
        try (var reader = Json.createReader(new ByteArrayInputStream(bytes))) {
            structure = reader.read();
        } catch (JsonException | IllegalStateException e) {
            throw McpProtocolException.parseError("Parse error: invalid JSON");
        }
        if (structure instanceof JsonObject object) {
            return object;
        }
        throw McpProtocolException.invalidRequest("The body must be a single JSON-RPC request or notification (batches are not supported)");
    }

    private static JsonValue requestId(JsonValue id) {
        if (id instanceof JsonString || (id instanceof JsonNumber n && n.isIntegral())) {
            return id;
        }
        throw McpProtocolException.invalidRequest("id must be a string or an integer");
    }

    /** Everything that can reject a request before any work is done: feature availability and the transport headers. */
    private RequestContext prepare(String method, JsonObject message, Function<String, String> headers, Caller caller) {
        if (method.startsWith("prompts/") && !registry.hasPrompts() || method.startsWith("resources/") && !registry.hasResources() || method.equals("completion/complete") && !registry.hasCompletions()) {
            throw McpProtocolException.methodNotFound(method);
        }
        if (method.startsWith("tasks/") && !tasks.enabled()) {
            throw McpProtocolException.methodNotFound(method);
        }
        if ((method.startsWith("skills/") || method.equals("resources/directory/read")) && !registry.hasSkills()) {
            throw McpProtocolException.methodNotFound(method);
        }
        return validate(method, message, headers, caller);
    }

    private JsonObject execute(RequestContext context) {
        String method = context.method();
        JsonObject result = switch (method) {
            case "server/discover" -> discover();
            case "tools/list" -> toolsList(context);
            case "tools/call" -> toolsCall(context);
            case "prompts/list" -> promptsList(context);
            case "prompts/get" -> promptsGet(context);
            case "resources/list" -> resourcesList(context);
            case "resources/templates/list" -> resourceTemplatesList(context);
            case "resources/read" -> resourcesRead(context);
            case "completion/complete" -> completionComplete(context);
            case "skills/list" -> skillsList(context);
            case "skills/get" -> skillsGet(context);
            case "resources/directory/read" -> directoryRead(context);
            case "tasks/get" -> tasksGet(context);
            case "tasks/update" -> tasksUpdate(context);
            case "tasks/cancel" -> tasksCancel(context);
            default -> throw McpProtocolException.methodNotFound(method);
        };
        return withServerInfo(result);
    }

    /** Checks the transport headers against the body, in the order that keeps unknown future versions from tripping over today's rules. */
    private static RequestContext validate(String method, JsonObject message, Function<String, String> headers, Caller caller) {
        String versionHeader = headers.apply("MCP-Protocol-Version");
        if (versionHeader == null || versionHeader.isBlank()) {
            throw McpProtocolException.headerMismatch("Missing MCP-Protocol-Version header; this server supports " + String.join(", ", McpVersions.SUPPORTED));
        }
        versionHeader = versionHeader.trim();
        if (!McpVersions.isSupported(versionHeader)) {
            throw McpProtocolException.unsupportedVersion(abbreviate(versionHeader));
        }
        JsonObject params = objectOf(message.get("params"), "params");
        JsonObject meta = objectOf(params.get("_meta"), "params._meta");
        if (!(meta.get(MetaKeys.PROTOCOL_VERSION) instanceof JsonString metaVersion)) {
            throw McpProtocolException.malformedParams("_meta must contain " + MetaKeys.PROTOCOL_VERSION);
        }
        if (!metaVersion.getString().equals(versionHeader)) {
            throw McpProtocolException.headerMismatch("Header mismatch: MCP-Protocol-Version header value '" + abbreviate(versionHeader) + "' does not match body value '" + abbreviate(metaVersion.getString()) + "'");
        }
        JsonObject capabilities = objectOf(meta.get(MetaKeys.CLIENT_CAPABILITIES), "_meta." + MetaKeys.CLIENT_CAPABILITIES);
        String methodHeader = headers.apply("Mcp-Method");
        if (methodHeader == null) {
            throw McpProtocolException.headerMismatch("Missing Mcp-Method header");
        }
        if (!methodHeader.equals(method)) {
            throw McpProtocolException.headerMismatch("Header mismatch: Mcp-Method header value '" + abbreviate(methodHeader) + "' does not match body value '" + abbreviate(method) + "'");
        }
        String nameField = switch (method) {
            case "tools/call", "prompts/get" -> "name";
            case "resources/read" -> "uri";
            case "tasks/get", "tasks/update", "tasks/cancel" -> "taskId";
            default -> null;
        };
        if (nameField != null) {
            checkNameHeader(headers.apply("Mcp-Name"), params, nameField);
        }
        JsonObject clientInfo = meta.get(MetaKeys.CLIENT_INFO) instanceof JsonObject info ? info : null;
        return new RequestContext(method, params, capabilities, clientInfo, caller, TraceContext.from(meta), headers);
    }

    private static void checkNameHeader(String header, JsonObject params, String field) {
        if (header == null) {
            throw McpProtocolException.headerMismatch("Missing Mcp-Name header");
        }
        if (!(params.get(field) instanceof JsonString bodyValue)) {
            throw McpProtocolException.malformedParams("params." + field + " must be a string");
        }
        String decoded;
        try {
            decoded = HeaderValues.decode(header);
        } catch (IllegalArgumentException e) {
            throw McpProtocolException.headerMismatch("Mcp-Name header is not valid base64");
        }
        if (!decoded.equals(bodyValue.getString())) {
            throw McpProtocolException.headerMismatch("Header mismatch: Mcp-Name header value '" + abbreviate(decoded) + "' does not match body value '" + abbreviate(bodyValue.getString()) + "'");
        }
    }

    private JsonObject discover() {
        JsonObjectBuilder result = Json.createObjectBuilder().add("resultType", "complete").add("supportedVersions", stringArray(McpVersions.SUPPORTED));
        JsonObjectBuilder capabilities = Json.createObjectBuilder().add("tools", Json.createObjectBuilder().add("listChanged", true));
        if (registry.hasPrompts()) {
            capabilities.add("prompts", Json.createObjectBuilder().add("listChanged", true));
        }
        if (registry.hasResources()) {
            capabilities.add("resources", Json.createObjectBuilder().add("subscribe", true).add("listChanged", true));
        }
        if (registry.hasCompletions()) {
            capabilities.add("completions", Json.createObjectBuilder());
        }
        if (!extensions.isEmpty() || registry.hasSkills() || tasks.enabled()) {
            JsonObjectBuilder announced = Json.createObjectBuilder(extensions.toJson());
            if (tasks.enabled() && !extensions.announces(Tasks.EXTENSION_ID)) {
                announced.add(Tasks.EXTENSION_ID, Json.createObjectBuilder());
            }
            if (registry.hasSkills() && !extensions.announces(SkillSet.EXTENSION_ID)) {
                announced.add(SkillSet.EXTENSION_ID, Json.createObjectBuilder().add("directoryRead", true));
            }
            capabilities.add("extensions", announced);
        }
        result.add("capabilities", capabilities);
        if (config.instructions() != null) {
            result.add("instructions", config.instructions());
        }
        return result.add("ttlMs", config.cacheTtlMs()).add("cacheScope", "public").build();
    }

    private JsonObject toolsList(RequestContext context) {
        Page<McpToolHandler> page = page(context, registry.all(), McpToolHandler::requiredScopes, McpToolHandler::name);
        JsonArrayBuilder tools = Json.createArrayBuilder();
        for (McpToolHandler tool : page.items()) {
            JsonObjectBuilder entry = Json.createObjectBuilder().add("name", tool.name());
            if (tool.title() != null) {
                entry.add("title", tool.title());
            }
            if (tool.description() != null) {
                entry.add("description", tool.description());
            }
            entry.add("inputSchema", tool.inputSchema());
            if (tool.outputSchema() != null) {
                entry.add("outputSchema", tool.outputSchema());
            }
            if (tool.annotations() != null) {
                entry.add("annotations", tool.annotations());
            }
            if (tool.taskSupport() != null) {
                entry.add("execution", Json.createObjectBuilder().add("taskSupport", tool.taskSupport().wire()));
            }
            addDescriptors(entry, tool.icons(), tool.meta());
            tools.add(entry);
        }
        return listResult("tools", tools, page.nextCursor());
    }

    private JsonObject promptsList(RequestContext context) {
        Page<McpPromptHandler> page = page(context, registry.prompts(), McpPromptHandler::requiredScopes, McpPromptHandler::name);
        JsonArrayBuilder prompts = Json.createArrayBuilder();
        for (McpPromptHandler prompt : page.items()) {
            JsonObjectBuilder entry = Json.createObjectBuilder().add("name", prompt.name());
            addIfPresent(entry, "title", prompt.title());
            addIfPresent(entry, "description", prompt.description());
            if (!prompt.arguments().isEmpty()) {
                JsonArrayBuilder arguments = Json.createArrayBuilder();
                for (PromptArgument argument : prompt.arguments()) {
                    JsonObjectBuilder a = Json.createObjectBuilder().add("name", argument.name());
                    addIfPresent(a, "title", argument.title());
                    addIfPresent(a, "description", argument.description());
                    arguments.add(a.add("required", argument.required()));
                }
                entry.add("arguments", arguments);
            }
            addDescriptors(entry, prompt.icons(), prompt.meta());
            prompts.add(entry);
        }
        return listResult("prompts", prompts, page.nextCursor());
    }

    private JsonObject promptsGet(RequestContext context) {
        JsonObject params = context.params();
        String name = ((JsonString) params.get("name")).getString();
        McpPromptHandler prompt = registry.findPrompt(name).orElseThrow(() -> McpProtocolException.invalidArguments("Unknown prompt: " + abbreviate(name)));
        if (!visible(context.caller(), prompt.requiredScopes())) {
            throw new AuthChallengeException("Insufficient scope for prompt " + abbreviate(name), String.join(" ", prompt.requiredScopes()));
        }
        JsonObject rawArguments = optionalObject(params.get("arguments"), "params.arguments");
        Map<String, String> arguments = new LinkedHashMap<>();
        if (rawArguments != null) {
            Set<String> declared = new HashSet<>();
            prompt.arguments().forEach(a -> declared.add(a.name()));
            for (Map.Entry<String, JsonValue> entry : rawArguments.entrySet()) {
                if (!declared.contains(entry.getKey())) {
                    throw McpProtocolException.invalidArguments("Unknown argument for prompt " + name + ": " + abbreviate(entry.getKey()));
                }
                if (!(entry.getValue() instanceof JsonString value)) {
                    throw McpProtocolException.invalidArguments("Argument " + entry.getKey() + " of prompt " + name + " must be a string");
                }
                arguments.put(entry.getKey(), value.getString());
            }
        }
        for (PromptArgument argument : prompt.arguments()) {
            if (argument.required() && !arguments.containsKey(argument.name())) {
                throw McpProtocolException.invalidArguments("Missing required argument for prompt " + name + ": " + argument.name());
            }
        }
        JsonObject inputResponses = optionalObject(params.get("inputResponses"), "params.inputResponses");
        String requestState = optionalString(params.get("requestState"), "params.requestState");
        PromptResult outcome;
        try {
            outcome = prompt.get(new PromptCall(name, arguments, context.clientCapabilities(), context.clientInfo(), inputResponses, requestState, context.caller(), context.trace()));
        } catch (McpProtocolException e) {
            throw e;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Prompt '" + name + "' failed", e);
            throw McpProtocolException.internal("Prompt failed");
        }
        if (outcome == null) {
            LOG.warning("Prompt '" + name + "' returned null");
            throw McpProtocolException.internal("Prompt failed");
        }
        JsonObjectBuilder result = Json.createObjectBuilder();
        switch (outcome) {
            case PromptResult.Complete complete -> {
                result.add("resultType", "complete");
                addIfPresent(result, "description", complete.description());
                JsonArrayBuilder messages = Json.createArrayBuilder();
                for (PromptMessage message : complete.messages()) {
                    messages.add(Json.createObjectBuilder().add("role", message.role()).add("content", message.content()));
                }
                result.add("messages", messages);
            }
            case PromptResult.InputRequired input -> addInputRequired(result, input.inputRequests(), input.requestState());
        }
        return result.build();
    }

    private JsonObject resourcesList(RequestContext context) {
        Page<McpResourceHandler> page = page(context, registry.resources(), McpResourceHandler::requiredScopes, McpResourceHandler::uri);
        JsonArrayBuilder resources = Json.createArrayBuilder();
        for (McpResourceHandler resource : page.items()) {
            JsonObjectBuilder entry = Json.createObjectBuilder().add("uri", resource.uri()).add("name", resource.name());
            addIfPresent(entry, "title", resource.title());
            addIfPresent(entry, "description", resource.description());
            addIfPresent(entry, "mimeType", resource.mimeType());
            if (resource.size() != null) {
                entry.add("size", resource.size());
            }
            if (resource.annotations() != null) {
                entry.add("annotations", resource.annotations());
            }
            addDescriptors(entry, resource.icons(), resource.meta());
            resources.add(entry);
        }
        return listResult("resources", resources, page.nextCursor());
    }

    private JsonObject resourceTemplatesList(RequestContext context) {
        Page<McpResourceTemplateHandler> page = page(context, registry.templates(), McpResourceTemplateHandler::requiredScopes, McpResourceTemplateHandler::uriTemplate);
        JsonArrayBuilder templates = Json.createArrayBuilder();
        for (McpResourceTemplateHandler template : page.items()) {
            JsonObjectBuilder entry = Json.createObjectBuilder().add("uriTemplate", template.uriTemplate()).add("name", template.name());
            addIfPresent(entry, "title", template.title());
            addIfPresent(entry, "description", template.description());
            addIfPresent(entry, "mimeType", template.mimeType());
            if (template.annotations() != null) {
                entry.add("annotations", template.annotations());
            }
            addDescriptors(entry, template.icons(), template.meta());
            templates.add(entry);
        }
        return listResult("resourceTemplates", templates, page.nextCursor());
    }

    private JsonObject completionComplete(RequestContext context) {
        JsonObject params = context.params();
        if (!(params.get("ref") instanceof JsonObject ref) || !(params.get("argument") instanceof JsonObject argument)) {
            throw McpProtocolException.malformedParams("params.ref and params.argument must be objects");
        }
        McpCompletionHandler.Kind kind;
        String target;
        if (ref.get("type") instanceof JsonString type && type.getString().equals("ref/prompt") && ref.get("name") instanceof JsonString name) {
            kind   = McpCompletionHandler.Kind.PROMPT;
            target = name.getString();
        } else if (ref.get("type") instanceof JsonString type && type.getString().equals("ref/resource") && ref.get("uri") instanceof JsonString uri) {
            kind   = McpCompletionHandler.Kind.RESOURCE_TEMPLATE;
            target = uri.getString();
        } else {
            throw McpProtocolException.invalidArguments("ref must be {\"type\":\"ref/prompt\",\"name\":...} or {\"type\":\"ref/resource\",\"uri\":...}");
        }
        if (!(argument.get("name") instanceof JsonString argumentName) || !(argument.get("value") instanceof JsonString argumentValue) || argumentValue.getString().length() > MAX_COMPLETION_VALUE) {
            throw McpProtocolException.invalidArguments("argument must have a string name and a string value of at most " + MAX_COMPLETION_VALUE + " characters");
        }
        Map<String, String> others = new LinkedHashMap<>();
        if (optionalObject(params.get("context"), "params.context") instanceof JsonObject completionContext && optionalObject(completionContext.get("arguments"), "params.context.arguments") instanceof JsonObject given) {
            if (given.size() > MAX_COMPLETION_CONTEXT) {
                throw McpProtocolException.invalidArguments("context.arguments has too many entries");
            }
            for (Map.Entry<String, JsonValue> entry : given.entrySet()) {
                if (!(entry.getValue() instanceof JsonString text) || text.getString().length() > MAX_COMPLETION_VALUE) {
                    throw McpProtocolException.invalidArguments("context.arguments values must be strings of at most " + MAX_COMPLETION_VALUE + " characters");
                }
                others.put(entry.getKey(), text.getString());
            }
        }
        String label = kind == McpCompletionHandler.Kind.PROMPT ? "prompt" : "resource template";
        var known = registry.completionTarget(kind, target).orElseThrow(() -> McpProtocolException.invalidArguments("Unknown " + label + ": " + abbreviate(target)));
        if (!known.arguments().contains(argumentName.getString())) {
            throw McpProtocolException.invalidArguments("Unknown argument " + abbreviate(argumentName.getString()) + " for " + label + " " + abbreviate(target));
        }
        if (!visible(context.caller(), known.requiredScopes())) {
            throw new AuthChallengeException("Insufficient scope for " + label + " " + abbreviate(target), String.join(" ", known.requiredScopes()));
        }
        CompletionResult outcome = CompletionResult.empty();
        McpCompletionHandler handler = registry.findCompletion(kind, target, argumentName.getString()).orElse(null);
        if (handler != null) {
            if (!visible(context.caller(), handler.requiredScopes())) {
                throw new AuthChallengeException("Insufficient scope for completions of " + abbreviate(target), String.join(" ", handler.requiredScopes()));
            }
            try {
                outcome = handler.complete(new CompletionCall(kind, target, argumentName.getString(), argumentValue.getString(), others, context.clientCapabilities(), context.clientInfo(), context.caller(), context.trace()));
            } catch (McpProtocolException e) {
                throw e;
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Completion for " + abbreviate(target) + " failed", e);
                throw McpProtocolException.internal("Completion failed");
            }
            if (outcome == null) {
                outcome = CompletionResult.empty();
            }
        }
        List<String> values = outcome.values().stream().filter(java.util.Objects::nonNull).toList();
        boolean cut = values.size() > MAX_COMPLETION_VALUES;
        if (cut) {
            values = values.subList(0, MAX_COMPLETION_VALUES);
        }
        boolean more = outcome.hasMore() != null ? outcome.hasMore() || cut : cut || outcome.total() != null && outcome.total() > values.size();
        JsonObjectBuilder completion = Json.createObjectBuilder().add("values", stringArray(values));
        if (outcome.total() != null) {
            completion.add("total", outcome.total());
        }
        return Json.createObjectBuilder().add("resultType", "complete").add("completion", completion.add("hasMore", more)).build();
    }

    // ---- skills ----------------------------------------------------------------------------------------------------

    private JsonObject skillsList(RequestContext context) {
        Page<SkillSet.Skill> page = page(context, registry.skills().list(), SkillSet.Skill::requiredScopes, SkillSet.Skill::name);
        JsonArrayBuilder skills = Json.createArrayBuilder();
        page.items().forEach(skill -> skills.add(skill.toJson()));
        return listResult("skills", skills, page.nextCursor());
    }

    private JsonObject skillsGet(RequestContext context) {
        String uri = uriParam(context);
        SkillSet.Skill skill = registry.skills().get(uri).orElseThrow(() -> unknownSkill(uri));
        if (!visible(context.caller(), skill.requiredScopes())) {
            throw new AuthChallengeException("Insufficient scope for skill " + abbreviate(skill.name()), String.join(" ", skill.requiredScopes()));
        }
        boolean perCaller = authConfig.enabled() && !registry.scopedPrimitives().isEmpty();
        return Json.createObjectBuilder().add("resultType", "complete").add("skill", skill.toJson()).add("ttlMs", config.cacheTtlMs()).add("cacheScope", perCaller ? "private" : "public").build();
    }

    private JsonObject directoryRead(RequestContext context) {
        String uri = uriParam(context);
        SkillSet.Directory directory = registry.skills().directory(uri).orElseThrow(() -> unknownSkill(uri));
        if (!visible(context.caller(), directory.skill().requiredScopes())) {
            throw new AuthChallengeException("Insufficient scope for skill " + abbreviate(directory.skill().name()), String.join(" ", directory.skill().requiredScopes()));
        }
        List<Map.Entry<String, String>> children = new ArrayList<>(directory.children().entrySet());
        int from = 0;
        JsonValue cursor = context.params().get("cursor");
        if (cursor != null && cursor != JsonValue.NULL) {
            String after = decodeCursor(cursor);
            while (from < children.size() && children.get(from).getKey().compareTo(after) <= 0) {
                from++;
            }
        }
        int size = config.pageSize();
        int to = size > 0 ? Math.min(children.size(), from + size) : children.size();
        JsonArrayBuilder resources = Json.createArrayBuilder();
        for (Map.Entry<String, String> child : children.subList(from, to)) {
            resources.add(Json.createObjectBuilder().add("uri", uri + "/" + child.getKey()).add("name", child.getKey()).add("mimeType", child.getValue()));
        }
        JsonObjectBuilder result = Json.createObjectBuilder().add("resultType", "complete").add("resources", resources);
        if (to < children.size()) {
            result.add("nextCursor", encodeCursor(children.get(to - 1).getKey()));
        }
        boolean perCaller = authConfig.enabled() && !registry.scopedPrimitives().isEmpty();
        return result.add("ttlMs", config.cacheTtlMs()).add("cacheScope", perCaller ? "private" : "public").build();
    }

    private static String uriParam(RequestContext context) {
        if (!(context.params().get("uri") instanceof JsonString uri) || uri.getString().length() > MAX_URI) {
            throw McpProtocolException.malformedParams("params.uri must be a string of at most " + MAX_URI + " characters");
        }
        return uri.getString();
    }

    private static McpProtocolException unknownSkill(String uri) {
        return new McpProtocolException(200, ErrorCodes.INVALID_PARAMS, "Unknown skill or directory", Json.createObjectBuilder().add("uri", abbreviate(uri)).build());
    }

    private JsonObject resourcesRead(RequestContext context) {
        JsonObject params = context.params();
        String uri = ((JsonString) params.get("uri")).getString();
        if (uri.length() > MAX_URI || !ABSOLUTE_URI.matcher(uri).matches()) {
            throw McpProtocolException.invalidArguments("Invalid resource URI");
        }
        var resolved = registry.resolve(uri).orElseThrow(() -> new McpProtocolException(200, ErrorCodes.INVALID_PARAMS, "Resource not found", Json.createObjectBuilder().add("uri", abbreviate(uri)).build()));
        if (!visible(context.caller(), resolved.requiredScopes())) {
            throw new AuthChallengeException("Insufficient scope for resource " + abbreviate(uri), String.join(" ", resolved.requiredScopes()));
        }
        JsonObject inputResponses = optionalObject(params.get("inputResponses"), "params.inputResponses");
        String requestState = optionalString(params.get("requestState"), "params.requestState");
        ResourceResult outcome;
        try {
            outcome = resolved.read(new ResourceCall(uri, resolved.variables(), context.clientCapabilities(), context.clientInfo(), inputResponses, requestState, context.caller(), context.trace()));
        } catch (McpProtocolException e) {
            throw e;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Reading resource '" + abbreviate(uri) + "' failed", e);
            throw McpProtocolException.internal("Resource read failed");
        }
        if (outcome == null) {
            LOG.warning("Reading resource '" + abbreviate(uri) + "' returned null");
            throw McpProtocolException.internal("Resource read failed");
        }
        JsonObjectBuilder result = Json.createObjectBuilder();
        switch (outcome) {
            case ResourceResult.Complete complete -> {
                result.add("resultType", "complete");
                JsonArrayBuilder contents = Json.createArrayBuilder();
                for (ResourceContent content : complete.contents()) {
                    contents.add(content.toJson());
                }
                result.add("contents", contents);
                result.add("ttlMs", complete.ttlMs() != null ? complete.ttlMs() : 0L);
                result.add("cacheScope", complete.cacheScope() != null ? complete.cacheScope() : (authConfig.enabled() ? "private" : "public"));
            }
            case ResourceResult.InputRequired input -> addInputRequired(result, input.inputRequests(), input.requestState());
        }
        return result.build();
    }

    private static void addInputRequired(JsonObjectBuilder result, JsonObject inputRequests, String requestState) {
        result.add("resultType", "input_required");
        if (inputRequests != null) {
            result.add("inputRequests", inputRequests);
        }
        if (requestState != null) {
            result.add("requestState", requestState);
        }
    }

    private static void addIfPresent(JsonObjectBuilder builder, String key, String value) {
        if (value != null) {
            builder.add(key, value);
        }
    }

    /**
     * Keyset pagination over the items the caller may see. The cursor is the base64url of {@code "k1:"} plus the key of the last item
     * returned, and the next page is everything that sorts after it, so it needs no server state, survives restarts and stays
     * correct when items are added. It holds nothing secret and forging one only chooses a starting point in a list the caller is
     * allowed to see anyway, because scope filtering happens on every page. With {@code mcp.page-size} unset no cursor is ever issued, so any
     * cursor is invalid.
     */
    private <T> Page<T> page(RequestContext context, List<T> all, java.util.function.Function<T, Set<String>> scopes, java.util.function.Function<T, String> key) {
        JsonValue cursor = context.params().get("cursor");
        String after = null;
        if (cursor != null && cursor != JsonValue.NULL) {
            after = decodeCursor(cursor);
        }
        List<T> visible = new ArrayList<>();
        for (T item : all) {
            if (visible(context.caller(), scopes.apply(item))) {
                visible.add(item);
            }
        }
        visible.sort(Comparator.comparing(key));
        int size = config.pageSize();
        if (size <= 0) {
            return new Page<>(visible, null);
        }
        List<T> remaining = new ArrayList<>();
        for (T item : visible) {
            if (after == null || key.apply(item).compareTo(after) > 0) {
                remaining.add(item);
            }
        }
        if (remaining.size() <= size) {
            return new Page<>(remaining, null);
        }
        List<T> items = remaining.subList(0, size);
        return new Page<>(List.copyOf(items), encodeCursor(key.apply(items.get(size - 1))));
    }

    private String decodeCursor(JsonValue cursor) {
        if (config.pageSize() <= 0 || !(cursor instanceof JsonString text) || text.getString().length() > 4096) {
            throw McpProtocolException.invalidArguments("Invalid cursor");
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(text.getString()), StandardCharsets.UTF_8);
            if (decoded.startsWith("k1:")) {
                return decoded.substring(3);
            }
        } catch (IllegalArgumentException e) {
            // falls through to the error below
        }
        throw McpProtocolException.invalidArguments("Invalid cursor");
    }

    private static String encodeCursor(String key) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(("k1:" + key).getBytes(StandardCharsets.UTF_8));
    }

    private record Page<T>(List<T> items, String nextCursor) {
    }

    private static boolean visible(Caller caller, Set<String> requiredScopes) {
        return Scopes.satisfiesAll(caller.scopes(), requiredScopes);
    }

    /** A list may differ per caller only when some primitive needs scopes; then shared caches must not serve it to others. */
    private JsonObject listResult(String key, JsonArrayBuilder items, String nextCursor) {
        boolean perCaller = authConfig.enabled() && !registry.scopedPrimitives().isEmpty();
        JsonObjectBuilder result = Json.createObjectBuilder().add("resultType", "complete").add(key, items);
        if (nextCursor != null) {
            result.add("nextCursor", nextCursor);
        }
        return result.add("ttlMs", config.cacheTtlMs()).add("cacheScope", perCaller ? "private" : "public").build();
    }

    private static final int MAX_RESOURCE_SUBSCRIPTIONS = 100;

    /**
     * Opens a {@code subscriptions/listen} stream. The filter is checked here, before streaming starts, so a bad request still gets
     * a normal JSON error. The server answers only for what it can honor: list-changed for features it has, and resource URIs that
     * exist and that this caller may read; everything else is left out of the acknowledgement, as the spec says.
     */
    private McpHttpResponse listen(JsonValue id, RequestContext context) {
        if (!(context.params().get("notifications") instanceof JsonObject filter)) {
            throw McpProtocolException.malformedParams("params.notifications must be an object");
        }
        boolean tools     = flag(filter, "toolsListChanged");
        boolean prompts   = flag(filter, "promptsListChanged") && registry.hasPrompts();
        boolean resources = flag(filter, "resourcesListChanged") && registry.hasResources();
        Set<String> uris  = new java.util.LinkedHashSet<>();
        JsonValue requested = filter.get("resourceSubscriptions");
        if (requested != null && requested != JsonValue.NULL) {
            if (!(requested instanceof JsonArray array)) {
                throw McpProtocolException.malformedParams("resourceSubscriptions must be an array of strings");
            }
            if (array.size() > MAX_RESOURCE_SUBSCRIPTIONS) {
                throw McpProtocolException.invalidArguments("At most " + MAX_RESOURCE_SUBSCRIPTIONS + " resource subscriptions per stream");
            }
            for (JsonValue entry : array) {
                if (!(entry instanceof JsonString uri) || uri.getString().length() > MAX_URI) {
                    throw McpProtocolException.malformedParams("resourceSubscriptions must be an array of strings of at most " + MAX_URI + " characters");
                }
                boolean readable = registry.resolve(uri.getString()).filter(r -> Scopes.satisfiesAll(context.caller().scopes(), r.requiredScopes())).isPresent();
                if (readable) {
                    uris.add(uri.getString());
                }
            }
        }
        Instant expiresAt = authConfig.enabled() ? McpNotifications.expiry(context.caller().claims().get("exp")) : null;
        Subscription subscription = notifications.open(new Subscription(id, context.caller().subject(), expiresAt, tools, prompts, resources, uris));
        McpHttpResponse.Streaming stream = sink -> runListen(subscription, sink);
        return config.listenAsync() ? McpHttpResponse.detached(stream) : McpHttpResponse.streaming(stream);
    }

    private static boolean flag(JsonObject filter, String name) {
        JsonValue value = filter.get(name);
        if (value == null || value == JsonValue.NULL || value == JsonValue.FALSE) {
            return false;
        }
        if (value == JsonValue.TRUE) {
            return true;
        }
        throw McpProtocolException.malformedParams(name + " must be a boolean");
    }

    /**
     * Holds the stream open: acknowledgement first, then notifications as they come, a comment line when idle (which is also how a
     * vanished client is found: the write fails and the stream is released). Ends with a final response when the server closes it
     * (token expired, application stopping, client too slow); a client that closes the stream ends it from its side.
     */
    private void runListen(Subscription subscription, McpHttpResponse.EventSink sink) throws IOException {
        try {
            sink.send(subscription.acknowledgement());
            while (!subscription.isClosed()) {
                long wait = config.keepAliveMs();
                if (subscription.expiresAt() != null) {
                    long untilExpiry = java.time.Duration.between(Instant.now(), subscription.expiresAt()).toMillis();
                    if (untilExpiry <= 0) {
                        break;
                    }
                    wait = Math.min(wait, untilExpiry);
                }
                List<String> due;
                try {
                    due = subscription.await(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                for (String notification : due) {
                    sink.send(notification);
                }
                if (due.isEmpty() && !subscription.isClosed()) {
                    sink.comment("keep-alive");
                }
            }
            try {
                for (String notification : subscription.await(0)) {
                    sink.send(notification);   // whatever was already pending when the stream ended
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            sink.send(subscription.finalResponse());
        } finally {
            notifications.remove(subscription);
        }
    }

    private JsonObject tasksGet(RequestContext context) {
        Extensions.require(context.clientCapabilities(), Tasks.EXTENSION_ID);
        return tasks.get(taskId(context), context.caller());
    }

    private JsonObject tasksUpdate(RequestContext context) {
        Extensions.require(context.clientCapabilities(), Tasks.EXTENSION_ID);
        JsonObject responses = optionalObject(context.params().get("inputResponses"), "params.inputResponses");
        if (responses == null) {
            throw McpProtocolException.invalidArguments("params.inputResponses is required");
        }
        tasks.update(taskId(context), context.caller(), responses);
        return Json.createObjectBuilder().add("resultType", "complete").build();
    }

    private JsonObject tasksCancel(RequestContext context) {
        Extensions.require(context.clientCapabilities(), Tasks.EXTENSION_ID);
        tasks.cancel(taskId(context), context.caller());
        return Json.createObjectBuilder().add("resultType", "complete").build();
    }

    private static String taskId(RequestContext context) {
        return ((JsonString) context.params().get("taskId")).getString();
    }

    private JsonObject toolsCall(RequestContext context) {
        return invokeTool(prepareTool(context), ProgressReporter.NONE);
    }

    /** Looks the tool up and checks scopes and arguments, so that these errors keep their HTTP status even when the call is streamed. */
    private PreparedTool prepareTool(RequestContext context) {
        JsonObject params = context.params();
        String name = ((JsonString) params.get("name")).getString();
        McpToolHandler tool = registry.find(name).orElseThrow(() -> McpProtocolException.invalidArguments("Unknown tool: " + abbreviate(name)));
        JsonObject arguments = optionalObject(params.get("arguments"), "params.arguments");
        if (arguments == null) {
            arguments = JsonObject.EMPTY_JSON_OBJECT;
        }
        if (!Scopes.satisfiesAll(context.caller().scopes(), tool.requiredScopes())) {
            throw new AuthChallengeException("Insufficient scope for tool " + abbreviate(name), String.join(" ", tool.requiredScopes()));
        }
        if (tool.taskSupport() == TaskSupport.REQUIRED && !(tasks.enabled() && Tasks.clientSupports(context.clientCapabilities()))) {
            throw McpProtocolException.missingClientExtension(Tasks.EXTENSION_ID);
        }
        ParamHeaders.check(declaredHeaders(name, tool), arguments, context.headers());
        var problem = SchemaValidator.validate(tool.inputSchema(), arguments);
        if (problem.isPresent()) {
            throw McpProtocolException.invalidArguments("Invalid arguments for tool " + name + ": " + problem.get());
        }
        JsonObject inputResponses = optionalObject(params.get("inputResponses"), "params.inputResponses");
        String requestState = optionalString(params.get("requestState"), "params.requestState");
        return new PreparedTool(name, tool, arguments, inputResponses, requestState, context);
    }

    private static List<ParamHeaders.Param> declaredHeaders(String name, McpToolHandler tool) {
        try {
            return ParamHeaders.declared(tool.inputSchema());
        } catch (IllegalArgumentException e) {
            LOG.log(Level.WARNING, "Tool '" + name + "' has an invalid x-mcp-header: " + e.getMessage());
            throw McpProtocolException.invalidArguments("Tool " + name + " is misconfigured");
        }
    }

    private JsonObject invokeTool(PreparedTool prepared, ProgressReporter progress) {
        RequestContext context = prepared.context();
        ToolResult outcome;
        try {
            ToolCall call = new ToolCall(prepared.name(), prepared.arguments(), context.clientCapabilities(), context.clientInfo(), prepared.inputResponses(), prepared.requestState(), context.caller(), progress, context.trace());
            outcome = prepared.tool().call(call);
            if (outcome instanceof ToolResult.Task task) {
                if (tasks.enabled() && Tasks.clientSupports(context.clientCapabilities())) {
                    return tasks.create(task, call);
                }
                outcome = tasks.runInline(task, call);
            }
        } catch (McpProtocolException | RequestCancelledException e) {
            throw e;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Tool '" + prepared.name() + "' failed", e);
            outcome = ToolResult.error("Tool execution failed");
        }
        if (outcome == null) {
            LOG.warning("Tool '" + prepared.name() + "' returned null");
            outcome = ToolResult.error("Tool execution failed");
        }
        return toResult(outcome);
    }

    /** The {@code progressToken} of the request, or null if the client sent none. */
    private static JsonValue progressToken(RequestContext context) {
        JsonValue token = context.params().getJsonObject("_meta").get("progressToken");
        if (token == null || token == JsonValue.NULL) {
            return null;
        }
        if (token instanceof JsonString || (token instanceof JsonNumber number && number.isIntegral())) {
            return token;
        }
        throw McpProtocolException.malformedParams("_meta.progressToken must be a string or an integer");
    }

    /**
     * Runs the tool while the response is already streaming. The last event is the JSON-RPC response (a result, or an error that
     * can no longer change the HTTP status). If the client has closed the stream the request is cancelled and nothing more is sent.
     */
    private void streamTool(JsonValue id, PreparedTool prepared, JsonValue token, McpHttpResponse.EventSink sink) throws IOException {
        GuardedSink guarded   = new GuardedSink(sink);
        SseProgress progress  = new SseProgress(guarded, token);
        Thread      keepAlive = startKeepAlive(guarded, progress);
        try {
            String last;
            try {
                last = JsonRpc.result(id, withServerInfo(invokeTool(prepared, progress))).toString();
            } catch (RequestCancelledException e) {
                LOG.fine("Request cancelled: the client closed the stream");
                return;
            } catch (McpProtocolException e) {
                last = JsonRpc.error(id, e.code(), e.getMessage(), e.data()).toString();
            } catch (RuntimeException e) {
                LOG.log(Level.SEVERE, "Unexpected failure while streaming an MCP response", e);
                last = JsonRpc.error(id, ErrorCodes.INTERNAL_ERROR, "Internal error", null).toString();
            }
            if (!progress.isCancelled()) {
                guarded.send(last);
            }
        } finally {
            keepAlive.interrupt();
            guarded.close();
        }
    }

    /**
     * While a streamed call runs, writes a comment line whenever the stream has been quiet for {@code keepAliveMs}. This keeps proxies
     * from cutting a long call that reports no progress, and it finds a client that has gone: the failed write cancels the call
     * ({@link ToolCall#isCancelled()}, and the next progress report throws).
     */
    private Thread startKeepAlive(GuardedSink sink, SseProgress progress) {
        long interval = config.keepAliveMs();
        return Thread.ofVirtual().name("mcp-call-keepalive").start(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    long wait = interval - sink.idleMillis();
                    if (wait > 0) {
                        Thread.sleep(wait);
                    } else {
                        sink.comment("keep-alive");
                    }
                }
            } catch (InterruptedException e) {
                // the tool is done
            } catch (IOException e) {
                progress.cancel();
            }
        });
    }

    static JsonObject toResult(ToolResult outcome) {
        JsonObjectBuilder result = Json.createObjectBuilder();
        switch (outcome) {
            case ToolResult.Complete complete -> {
                result.add("resultType", "complete").add("content", complete.content());
                if (complete.structuredContent() != null) {
                    result.add("structuredContent", complete.structuredContent());
                }
                result.add("isError", complete.isError());
                if (complete.meta() != null) {
                    result.add("_meta", complete.meta());
                }
            }
            case ToolResult.Task task -> throw new IllegalStateException("A task is not a result");
            case ToolResult.InputRequired input -> {
                result.add("resultType", "input_required");
                if (input.inputRequests() != null) {
                    result.add("inputRequests", input.inputRequests());
                }
                if (input.requestState() != null) {
                    result.add("requestState", input.requestState());
                }
            }
        }
        return result.build();
    }

    /** Adds the server's identity to the result's {@code _meta}, keeping what the handler put there (the server's key wins on a clash). */
    private JsonObject withServerInfo(JsonObject result) {
        JsonObject info = Json.createObjectBuilder().add("name", config.serverName()).add("version", config.serverVersion()).build();
        JsonObjectBuilder meta = result.get("_meta") instanceof JsonObject existing ? Json.createObjectBuilder(existing) : Json.createObjectBuilder();
        return Json.createObjectBuilder(result).add("_meta", meta.add(MetaKeys.SERVER_INFO, info)).build();
    }

    /** The optional {@code icons} and {@code _meta} fields of a listed tool, prompt, resource or template. */
    private static void addDescriptors(JsonObjectBuilder entry, List<Icon> icons, JsonObject meta) {
        if (!icons.isEmpty()) {
            entry.add("icons", Icon.toJson(icons));
        }
        if (meta != null) {
            entry.add("_meta", meta);
        }
    }

    /** An optional object field: null when absent or JSON null, a malformed-params error for any other type. */
    private static JsonObject optionalObject(JsonValue value, String what) {
        if (value == null || value == JsonValue.NULL) {
            return null;
        }
        if (value instanceof JsonObject object) {
            return object;
        }
        throw McpProtocolException.malformedParams(what + " must be an object");
    }

    private static String optionalString(JsonValue value, String what) {
        if (value == null || value == JsonValue.NULL) {
            return null;
        }
        if (value instanceof JsonString string) {
            return string.getString();
        }
        throw McpProtocolException.malformedParams(what + " must be a string");
    }

    private static JsonObject objectOf(JsonValue value, String what) {
        if (value instanceof JsonObject object) {
            return object;
        }
        throw McpProtocolException.malformedParams("Missing or malformed " + what);
    }

    private static JsonArray stringArray(Iterable<String> values) {
        JsonArrayBuilder array = Json.createArrayBuilder();
        values.forEach(array::add);
        return array.build();
    }

    /** Client-supplied text is echoed in error messages; cap it so a hostile client cannot make us reflect megabytes. */
    private static String abbreviate(String value) {
        return value.length() <= 100 ? value : value.substring(0, 100) + "...";
    }

    private record PreparedTool(String name, McpToolHandler tool, JsonObject arguments, JsonObject inputResponses, String requestState, RequestContext context) {
    }

    private record RequestContext(String method, JsonObject params, JsonObject clientCapabilities, JsonObject clientInfo, Caller caller, TraceContext trace, Function<String, String> headers) {
    }
}
