package eu.hansolo.mcp.tools;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import eu.hansolo.mcp.api.Caller;
import eu.hansolo.mcp.api.Icon;
import eu.hansolo.mcp.api.McpParam;
import eu.hansolo.mcp.api.McpTool;
import eu.hansolo.mcp.api.McpToolHandler;
import eu.hansolo.mcp.api.ToolCall;
import eu.hansolo.mcp.api.ToolExecutionException;
import eu.hansolo.mcp.api.ToolResult;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** A {@link McpToolHandler} backed by one {@link McpTool} method. Schemas are computed once, when the handler is created. */
public final class AnnotatedToolHandler implements McpToolHandler {

    private final String        name;
    private final String        title;
    private final String        description;
    private final Set<String>   scopes;
    private final JsonObject    inputSchema;
    private final JsonObject    outputSchema;
    private final JsonObject    annotations;
    private final List<Icon>    icons;
    private final JsonObject    meta;
    private final Method        method;
    private final List<Binding> bindings;
    private final BeanSource    source;


    AnnotatedToolHandler(Method method, BeanSource source) {
        McpTool tool = method.getAnnotation(McpTool.class);
        String where = method.getDeclaringClass().getName() + "." + method.getName();
        this.method       = method;
        this.source       = source;
        this.name         = tool.name().isEmpty() ? method.getName() : tool.name();
        this.title        = tool.title().isEmpty() ? null : tool.title();
        this.description  = tool.description().isEmpty() ? null : tool.description();
        this.scopes       = Set.copyOf(new LinkedHashSet<>(List.of(tool.scopes())));
        this.bindings     = bind(method, where);
        this.inputSchema  = inputSchema(bindings, where);
        this.outputSchema = ResultMapper.hasOutputSchema(method.getReturnType()) ? SchemaGenerator.schemaFor(method.getGenericReturnType()) : null;
        this.annotations  = annotations(tool);
        this.icons        = Descriptors.icons(tool.icons(), where);
        this.meta         = Descriptors.meta(tool.meta(), where);
        method.setAccessible(true);
    }


    @Override
    public List<Icon> icons() {
        return icons;
    }

    @Override
    public JsonObject meta() {
        return meta;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public JsonObject inputSchema() {
        return inputSchema;
    }

    @Override
    public JsonObject outputSchema() {
        return outputSchema;
    }

    @Override
    public JsonObject annotations() {
        return annotations;
    }

    @Override
    public Set<String> requiredScopes() {
        return scopes;
    }

    @Override
    public ToolResult call(ToolCall call) throws Exception {
        Object[] arguments = new Object[bindings.size()];
        for (int i = 0; i < arguments.length; i++) {
            Binding binding = bindings.get(i);
            arguments[i] = switch (binding.kind()) {
                case CALL -> call;
                case CALLER -> call.caller();
                case ARGUMENT -> JsonConverter.argument(call.arguments().get(binding.name()), binding.type(), binding.name(), binding.required());
            };
        }
        try (BeanSource.Lease lease = source.acquire()) {
            return ResultMapper.toResult(method.invoke(lease.instance(), arguments));
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ToolExecutionException failure) {
                return ToolResult.error(failure.getMessage());
            }
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }

    private static List<Binding> bind(Method method, String where) {
        List<Binding> result = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (Parameter parameter : method.getParameters()) {
            Type type = parameter.getParameterizedType();
            if (parameter.getType() == ToolCall.class) {
                result.add(new Binding(Kind.CALL, null, type, false, "", ""));
            } else if (parameter.getType() == Caller.class) {
                result.add(new Binding(Kind.CALLER, null, type, false, "", ""));
            } else {
                McpParam info = parameter.getAnnotation(McpParam.class);
                String parameterName = info != null && !info.name().isEmpty() ? info.name() : parameter.getName();
                if (info == null || info.name().isEmpty()) {
                    if (!parameter.isNamePresent()) {
                        throw new IllegalStateException(where + ": parameter names are not available; compile with -parameters or use @McpParam(name = ...)");
                    }
                }
                if (!names.add(parameterName)) {
                    throw new IllegalStateException(where + ": duplicate parameter name '" + parameterName + "'");
                }
                try {
                    result.add(new Binding(Kind.ARGUMENT, parameterName, type, SchemaGenerator.isRequired(type, info), info == null ? "" : info.description(), info == null ? "" : info.header()));
                } catch (IllegalStateException e) {
                    throw new IllegalStateException(where + ", parameter '" + parameterName + "': " + e.getMessage(), e);
                }
            }
        }
        return List.copyOf(result);
    }

    private static JsonObject inputSchema(List<Binding> bindings, String where) {
        JsonObjectBuilder properties = Json.createObjectBuilder();
        JsonArrayBuilder required = Json.createArrayBuilder();
        SchemaGenerator.Defs defs = new SchemaGenerator.Defs();
        boolean anyRequired = false;
        for (Binding binding : bindings) {
            if (binding.kind() != Kind.ARGUMENT) {
                continue;
            }
            try {
                properties.add(binding.name(), SchemaGenerator.withHeader(SchemaGenerator.withDescription(SchemaGenerator.schemaFor(binding.type(), defs), binding.description()), binding.header()));
            } catch (IllegalStateException e) {
                throw new IllegalStateException(where + ", parameter '" + binding.name() + "': " + e.getMessage(), e);
            }
            if (binding.required()) {
                required.add(binding.name());
                anyRequired = true;
            }
        }
        JsonObjectBuilder schema = Json.createObjectBuilder().add("type", "object").add("properties", properties);
        if (anyRequired) {
            schema.add("required", required);
        }
        schema.add("additionalProperties", false);
        defs.addTo(schema);
        return schema.build();
    }

    private static JsonObject annotations(McpTool tool) {
        JsonObjectBuilder hints = Json.createObjectBuilder();
        boolean any = false;
        any |= hint(hints, "readOnlyHint", tool.readOnlyHint());
        any |= hint(hints, "destructiveHint", tool.destructiveHint());
        any |= hint(hints, "idempotentHint", tool.idempotentHint());
        any |= hint(hints, "openWorldHint", tool.openWorldHint());
        return any ? hints.build() : null;
    }

    private static boolean hint(JsonObjectBuilder into, String key, McpTool.Hint hint) {
        if (hint == McpTool.Hint.UNSET) {
            return false;
        }
        into.add(key, hint == McpTool.Hint.TRUE);
        return true;
    }


    private enum Kind {
        ARGUMENT, CALL, CALLER
    }


    private record Binding(Kind kind, String name, Type type, boolean required, String description, String header) {
    }
}
