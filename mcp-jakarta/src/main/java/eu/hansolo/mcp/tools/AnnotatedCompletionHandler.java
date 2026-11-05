package eu.hansolo.mcp.tools;

import eu.hansolo.mcp.api.Caller;
import eu.hansolo.mcp.api.CompletionCall;
import eu.hansolo.mcp.api.CompletionResult;
import eu.hansolo.mcp.api.McpComplete;
import eu.hansolo.mcp.api.McpCompletionHandler;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** A completion source backed by one {@link McpComplete} method. */
public final class AnnotatedCompletionHandler implements McpCompletionHandler {

    private final Kind        kind;
    private final String      target;
    private final String      argument;
    private final Set<String> scopes;
    private final Method      method;
    private final BeanSource  source;
    private final List<Bind>  bindings;
    private final boolean     returnsResult;


    AnnotatedCompletionHandler(Method method, BeanSource source) {
        McpComplete annotation = method.getAnnotation(McpComplete.class);
        String where = method.getDeclaringClass().getName() + "." + method.getName();
        boolean prompt = !annotation.prompt().isEmpty();
        if (prompt == !annotation.resourceTemplate().isEmpty()) {
            throw new IllegalStateException(where + ": @McpComplete needs exactly one of prompt and resourceTemplate");
        }
        this.kind     = prompt ? Kind.PROMPT : Kind.RESOURCE_TEMPLATE;
        this.target   = prompt ? annotation.prompt() : annotation.resourceTemplate();
        this.argument = annotation.argument();
        this.scopes   = Set.copyOf(new LinkedHashSet<>(List.of(annotation.scopes())));
        this.method   = method;
        this.source   = source;
        this.bindings = bind(method, where);
        Class<?> raw = method.getReturnType();
        if (raw == CompletionResult.class) {
            this.returnsResult = true;
        } else if (raw == List.class && method.getGenericReturnType() instanceof ParameterizedType p && p.getActualTypeArguments()[0] == String.class) {
            this.returnsResult = false;
        } else {
            throw new IllegalStateException(where + ": unsupported completion return type " + method.getGenericReturnType().getTypeName() + " (use List<String> or CompletionResult)");
        }
        method.setAccessible(true);
    }


    @Override
    public Kind kind() {
        return kind;
    }

    @Override
    public String target() {
        return target;
    }

    @Override
    public String argument() {
        return argument;
    }

    @Override
    public Set<String> requiredScopes() {
        return scopes;
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletionResult complete(CompletionCall call) throws Exception {
        Object[] values = new Object[bindings.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = switch (bindings.get(i)) {
                case TYPED -> call.value();
                case CALL -> call;
                case CALLER -> call.caller();
            };
        }
        try (BeanSource.Lease lease = source.acquire()) {
            Object result = method.invoke(lease.instance(), values);
            if (result == null) {
                return CompletionResult.empty();
            }
            return returnsResult ? (CompletionResult) result : CompletionResult.of((List<String>) result);
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

    private static List<Bind> bind(Method method, String where) {
        List<Bind> result = new ArrayList<>();
        boolean typed = false;
        for (Parameter parameter : method.getParameters()) {
            if (parameter.getType() == CompletionCall.class) {
                result.add(Bind.CALL);
            } else if (parameter.getType() == Caller.class) {
                result.add(Bind.CALLER);
            } else if (parameter.getType() == String.class) {
                if (typed) {
                    throw new IllegalStateException(where + ": at most one String parameter (the text typed so far)");
                }
                typed = true;
                result.add(Bind.TYPED);
            } else {
                throw new IllegalStateException(where + ", parameter of type " + parameter.getType().getSimpleName() + ": use String, CompletionCall or Caller");
            }
        }
        return List.copyOf(result);
    }


    private enum Bind {
        TYPED, CALL, CALLER
    }
}
