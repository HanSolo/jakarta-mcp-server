package eu.hansolo.mcp.tools;

import jakarta.json.JsonValue;
import eu.hansolo.mcp.api.Caller;
import eu.hansolo.mcp.api.McpResource;
import eu.hansolo.mcp.api.ResourceCall;
import eu.hansolo.mcp.api.ResourceContent;
import eu.hansolo.mcp.api.ResourceResult;
import eu.hansolo.mcp.server.UriTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The reflective part shared by fixed and templated annotated resources: parameter binding, invocation and result mapping. */
final class ResourceMethod {

    private final Method         method;
    private final BeanSource     source;
    private final String         mimeType;
    private final List<Binding>  bindings;
    private final ReturnKind     returnKind;


    ResourceMethod(Method method, BeanSource source, String mimeType, UriTemplate template) {
        String where = method.getDeclaringClass().getName() + "." + method.getName();
        this.method     = method;
        this.source     = source;
        this.bindings   = bind(method, template == null ? Set.of() : Set.copyOf(template.variables()), where);
        this.returnKind = returnKind(method, where);
        this.mimeType   = !mimeType.isEmpty() ? mimeType : returnKind.defaultMimeType();
        method.setAccessible(true);
    }


    String mimeType() {
        return mimeType;
    }

    ResourceResult read(ResourceCall call) throws Exception {
        Object[] arguments = new Object[bindings.size()];
        for (int i = 0; i < arguments.length; i++) {
            Binding binding = bindings.get(i);
            arguments[i] = switch (binding.kind()) {
                case CALL -> call;
                case CALLER -> call.caller();
                case VARIABLE -> call.variables().get(binding.name());
            };
        }
        try (BeanSource.Lease lease = source.acquire()) {
            return toResult(method.invoke(lease.instance(), arguments), call.uri());
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
    private ResourceResult toResult(Object value, String uri) {
        if (value == null) {
            throw new IllegalStateException("Resource method returned null");
        }
        return switch (returnKind) {
            case RESULT -> (ResourceResult) value;
            case CONTENT -> ResourceResult.of((ResourceContent) value);
            case CONTENT_LIST -> ResourceResult.of((List<ResourceContent>) value);
            case TEXT -> ResourceResult.text(uri, mimeType, value.toString());
            case BINARY -> ResourceResult.of(ResourceContent.blob(uri, mimeType, (byte[]) value));
            case JSON -> ResourceResult.text(uri, mimeType, JsonMapper.toJson(value).toString());
        };
    }

    private static List<Binding> bind(Method method, Set<String> templateVariables, String where) {
        List<Binding> result = new ArrayList<>();
        for (var parameter : method.getParameters()) {
            if (parameter.getType() == ResourceCall.class) {
                result.add(new Binding(Kind.CALL, null));
            } else if (parameter.getType() == Caller.class) {
                result.add(new Binding(Kind.CALLER, null));
            } else {
                if (!parameter.isNamePresent()) {
                    throw new IllegalStateException(where + ": parameter names are not available; compile with -parameters");
                }
                if (parameter.getType() != String.class) {
                    throw new IllegalStateException(where + ", parameter '" + parameter.getName() + "': template variables are strings; use String, ResourceCall or Caller");
                }
                if (!templateVariables.contains(parameter.getName())) {
                    throw new IllegalStateException(where + ", parameter '" + parameter.getName() + "' does not match a variable of the URI template " + templateVariables);
                }
                result.add(new Binding(Kind.VARIABLE, parameter.getName()));
            }
        }
        return List.copyOf(result);
    }

    private static ReturnKind returnKind(Method method, String where) {
        Class<?> raw = method.getReturnType();
        Type generic = method.getGenericReturnType();
        if (ResourceResult.class.isAssignableFrom(raw)) {
            return ReturnKind.RESULT;
        }
        if (raw == ResourceContent.class) {
            return ReturnKind.CONTENT;
        }
        if (raw == List.class && generic instanceof ParameterizedType p && p.getActualTypeArguments()[0] == ResourceContent.class) {
            return ReturnKind.CONTENT_LIST;
        }
        if (raw == String.class || raw == CharSequence.class) {
            return ReturnKind.TEXT;
        }
        if (raw == byte[].class) {
            return ReturnKind.BINARY;
        }
        if (raw == void.class || raw == Void.class || raw.isPrimitive()) {
            throw new IllegalStateException(where + ": a resource method must return a value");
        }
        if (JsonValue.class.isAssignableFrom(raw) || raw.isRecord() || Map.class.isAssignableFrom(raw) || List.class.isAssignableFrom(raw)) {
            return ReturnKind.JSON;
        }
        throw new IllegalStateException(where + ": unsupported resource return type " + generic.getTypeName() + " (use String, byte[], ResourceContent, List<ResourceContent>, ResourceResult, a record, Map, List or a JSON-P value)");
    }


    private enum Kind {
        CALL, CALLER, VARIABLE
    }


    private enum ReturnKind {
        RESULT(""), CONTENT(""), CONTENT_LIST(""), TEXT("text/plain"), BINARY("application/octet-stream"), JSON("application/json");

        private final String defaultMimeType;


        ReturnKind(String defaultMimeType) {
            this.defaultMimeType = defaultMimeType;
        }


        String defaultMimeType() {
            return defaultMimeType;
        }
    }


    private record Binding(Kind kind, String name) {
    }
}
