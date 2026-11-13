package eu.hansolo.mcp.tools;

import jakarta.json.JsonObject;
import eu.hansolo.mcp.api.Caller;
import eu.hansolo.mcp.api.Icon;
import eu.hansolo.mcp.api.McpParam;
import eu.hansolo.mcp.api.McpPrompt;
import eu.hansolo.mcp.api.McpPromptHandler;
import eu.hansolo.mcp.api.PromptArgument;
import eu.hansolo.mcp.api.PromptCall;
import eu.hansolo.mcp.api.PromptMessage;
import eu.hansolo.mcp.api.PromptResult;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A prompt backed by one {@link McpPrompt} method. Arguments are strings, as the protocol defines them.
 *
 * <p><b>Internal.</b> Not part of the stable API: this class is public only because other packages of this library use it,
 * and it can change in any release. Applications should not call it.
 */
public final class AnnotatedPromptHandler implements McpPromptHandler {

    private final String                name;
    private final String                title;
    private final String                description;
    private final Set<String>           scopes;
    private final List<PromptArgument>  arguments;
    private final Method                method;
    private final List<Binding>         bindings;
    private final BeanSource            source;
    private final ReturnKind            returnKind;
    private final List<Icon>            icons;
    private final JsonObject            meta;


    AnnotatedPromptHandler(Method method, BeanSource source) {
        McpPrompt prompt = method.getAnnotation(McpPrompt.class);
        String where = method.getDeclaringClass().getName() + "." + method.getName();
        this.method      = method;
        this.source      = source;
        this.name        = prompt.name().isEmpty() ? method.getName() : prompt.name();
        this.title       = prompt.title().isEmpty() ? null : prompt.title();
        this.description = prompt.description().isEmpty() ? null : prompt.description();
        this.scopes      = Set.copyOf(new LinkedHashSet<>(List.of(prompt.scopes())));
        this.bindings    = bind(method, where);
        this.arguments   = bindings.stream().filter(b -> b.kind() == Kind.ARGUMENT).map(b -> new PromptArgument(b.name(), null, b.description().isEmpty() ? null : b.description(), b.required())).toList();
        this.returnKind  = returnKind(method, where);
        this.icons       = Descriptors.icons(prompt.icons(), where);
        this.meta        = Descriptors.meta(prompt.meta(), where);
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
    public List<PromptArgument> arguments() {
        return arguments;
    }

    @Override
    public Set<String> requiredScopes() {
        return scopes;
    }

    @Override
    public PromptResult get(PromptCall call) throws Exception {
        Object[] values = new Object[bindings.size()];
        for (int i = 0; i < values.length; i++) {
            Binding binding = bindings.get(i);
            values[i] = switch (binding.kind()) {
                case CALL -> call;
                case CALLER -> call.caller();
                case ARGUMENT -> binding.optionalWrapper() ? call.argument(binding.name()) : call.arguments().get(binding.name());
            };
        }
        try (BeanSource.Lease lease = source.acquire()) {
            return toResult(method.invoke(lease.instance(), values));
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    private PromptResult toResult(Object value) {
        if (value == null) {
            throw new IllegalStateException("Prompt method returned null");
        }
        return switch (returnKind) {
            case RESULT -> (PromptResult) value;
            case MESSAGE -> PromptResult.of((PromptMessage) value);
            case MESSAGES -> new PromptResult.Complete(null, (List<PromptMessage>) value);
            case TEXT -> PromptResult.of(PromptMessage.user(value.toString()));
        };
    }

    private static List<Binding> bind(Method method, String where) {
        List<Binding> result = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (Parameter parameter : method.getParameters()) {
            if (parameter.getType() == PromptCall.class) {
                result.add(new Binding(Kind.CALL, null, false, false, ""));
                continue;
            }
            if (parameter.getType() == Caller.class) {
                result.add(new Binding(Kind.CALLER, null, false, false, ""));
                continue;
            }
            McpParam info = parameter.getAnnotation(McpParam.class);
            boolean wrapper = parameter.getType() == Optional.class;
            boolean string = parameter.getType() == String.class;
            if (wrapper && !(parameter.getParameterizedType() instanceof ParameterizedType p && p.getActualTypeArguments()[0] == String.class)) {
                throw new IllegalStateException(where + ", parameter '" + parameter.getName() + "': prompt arguments are strings; use Optional<String>");
            }
            if (!wrapper && !string) {
                throw new IllegalStateException(where + ", parameter '" + parameter.getName() + "': prompt arguments are strings; use String or Optional<String>");
            }
            if (info == null || info.name().isEmpty()) {
                if (!parameter.isNamePresent()) {
                    throw new IllegalStateException(where + ": parameter names are not available; compile with -parameters or use @McpParam(name = ...)");
                }
            }
            String argumentName = info != null && !info.name().isEmpty() ? info.name() : parameter.getName();
            if (!names.add(argumentName)) {
                throw new IllegalStateException(where + ": duplicate parameter name '" + argumentName + "'");
            }
            boolean required = !wrapper && (info == null || info.required());
            result.add(new Binding(Kind.ARGUMENT, argumentName, wrapper, required, info == null ? "" : info.description()));
        }
        return List.copyOf(result);
    }

    private static ReturnKind returnKind(Method method, String where) {
        Class<?> raw = method.getReturnType();
        if (PromptResult.class.isAssignableFrom(raw)) {
            return ReturnKind.RESULT;
        }
        if (raw == PromptMessage.class) {
            return ReturnKind.MESSAGE;
        }
        if (raw == List.class && method.getGenericReturnType() instanceof ParameterizedType p && p.getActualTypeArguments()[0] == PromptMessage.class) {
            return ReturnKind.MESSAGES;
        }
        if (raw == String.class || raw == CharSequence.class) {
            return ReturnKind.TEXT;
        }
        throw new IllegalStateException(where + ": unsupported prompt return type " + method.getGenericReturnType().getTypeName() + " (use String, PromptMessage, List<PromptMessage> or PromptResult)");
    }


    private enum Kind {
        ARGUMENT, CALL, CALLER
    }


    private enum ReturnKind {
        RESULT, MESSAGE, MESSAGES, TEXT
    }


    private record Binding(Kind kind, String name, boolean optionalWrapper, boolean required, String description) {
    }
}
