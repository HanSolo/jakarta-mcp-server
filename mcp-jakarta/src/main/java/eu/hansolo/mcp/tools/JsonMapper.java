package eu.hansolo.mcp.tools;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonValue;
import eu.hansolo.mcp.api.McpParam;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The reverse of {@link JsonConverter}: Java values returned by tools to JSON. An empty {@code Optional} record component is omitted.
 *
 * <p><b>Internal.</b> Not part of the stable API: this class is public only because other packages of this library use it,
 * and it can change in any release. Applications should not call it.
 */
public final class JsonMapper {

    private static final int MAX_DEPTH = 32;


    private JsonMapper() {
    }


    public static JsonValue toJson(Object value) {
        return toJson(value, 0, new ArrayList<>());
    }

    /** {@code path} holds the beans that contain the value, outermost first. */
    private static JsonValue toJson(Object value, int depth, List<Object> path) {
        if (depth > MAX_DEPTH) {
            throw new IllegalStateException("Result is nested too deeply (cyclic structure?)");
        }
        if (value == null) {
            return JsonValue.NULL;
        }
        if (value instanceof JsonValue json) {
            return json;
        }
        if (value instanceof CharSequence text) {
            return Json.createValue(text.toString());
        }
        if (value instanceof Boolean bool) {
            return bool ? JsonValue.TRUE : JsonValue.FALSE;
        }
        if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return Json.createValue(((Number) value).intValue());
        }
        if (value instanceof Long number) {
            return Json.createValue(number);
        }
        if (value instanceof BigInteger number) {
            return Json.createValue(number);
        }
        if (value instanceof BigDecimal number) {
            return Json.createValue(number);
        }
        if (value instanceof Float || value instanceof Double) {
            return decimal((Number) value);
        }
        if (value instanceof Enum<?> constant) {
            return Json.createValue(constant.name());
        }
        if (ValueTypes.isValue(value)) {
            return Json.createValue(ValueTypes.format(value));
        }
        if (value instanceof Optional<?> optional) {
            return optional.isPresent() ? toJson(optional.get(), depth + 1, path) : JsonValue.NULL;
        }
        if (value instanceof Map<?, ?> map) {
            JsonObjectBuilder object = Json.createObjectBuilder();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                object.add(String.valueOf(entry.getKey()), toJson(entry.getValue(), depth + 1, path));
            }
            return object.build();
        }
        if (value instanceof Collection<?> collection) {
            JsonArrayBuilder array = Json.createArrayBuilder();
            collection.forEach(element -> array.add(toJson(element, depth + 1, path)));
            return array.build();
        }
        if (value.getClass().isArray()) {
            JsonArrayBuilder array = Json.createArrayBuilder();
            for (int i = 0; i < java.lang.reflect.Array.getLength(value); i++) {
                array.add(toJson(java.lang.reflect.Array.get(value, i), depth + 1, path));
            }
            return array.build();
        }
        if (value.getClass().isRecord()) {
            return record(value, depth, path);
        }
        if (Beans.isBean(value.getClass())) {
            return bean(value, depth, path);
        }
        throw new IllegalStateException("Cannot convert " + value.getClass().getName() + " to JSON: return a String, a number, a java.time value, a UUID, a record, a bean with getters, a Map, a collection, or a JSON-P value");
    }

    /** An object made of the getters and public fields; null and empty {@code Optional} properties are left out. */
    private static JsonValue bean(Object value, int depth, List<Object> path) {
        Class<?> type = value.getClass();
        String refusal = Beans.refusal(type);
        if (refusal != null) {
            throw new IllegalStateException("Cannot convert " + refusal + " to JSON: copy it into a record or a DTO first");
        }
        for (Object outer : path) {
            if (outer == value) {
                throw new IllegalStateException("Cannot convert " + type.getName() + " to JSON: it contains itself (" + describe(path, type) + "); return a record or a DTO without the back reference");
            }
        }
        if (path.size() >= Beans.MAX_DEPTH) {
            throw new IllegalStateException("Cannot convert " + type.getName() + " to JSON: beans are nested deeper than " + Beans.MAX_DEPTH + " levels (" + describe(path, type) + "); return a record or a DTO");
        }
        List<Beans.Property> properties = Beans.properties(type);
        if (properties.isEmpty()) {
            throw new IllegalStateException("Cannot convert " + type.getName() + " to JSON: it has no public getters or fields");
        }
        path.add(value);
        try {
            JsonObjectBuilder object = Json.createObjectBuilder();
            for (Beans.Property property : properties) {
                Object member;
                try {
                    member = property.read(value);
                } catch (InvocationTargetException e) {
                    throw new IllegalStateException("Cannot read property '" + property.name() + "' of " + type.getName() + ": " + e.getCause(), e.getCause());
                } catch (ReflectiveOperationException | RuntimeException e) {
                    throw new IllegalStateException("Cannot read property '" + property.name() + "' of " + type.getName(), e);
                }
                if (member == null || member instanceof Optional<?> optional && optional.isEmpty()) {
                    continue;
                }
                McpParam info = property.info();
                object.add(info != null && !info.name().isEmpty() ? info.name() : property.name(), toJson(member, depth + 1, path));
            }
            return object.build();
        } finally {
            path.remove(path.size() - 1);
        }
    }

    private static String describe(List<Object> path, Class<?> last) {
        StringBuilder text = new StringBuilder();
        for (Object outer : path) {
            text.append(outer.getClass().getSimpleName()).append(" -> ");
        }
        return text.append(last.getSimpleName()).toString();
    }

    /** A whole {@code double} is written without a fraction ({@code 5}, not {@code 5.0}); a {@code float} keeps the digits it prints with ({@code 0.1}, not {@code 0.10000000149011612}). */
    private static JsonValue decimal(Number value) {
        double d = value.doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            return Json.createValue(d);   // JSON-P refuses these, as it always did
        }
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            return Json.createValue((long) d);
        }
        return value instanceof Float ? Json.createValue(new BigDecimal(value.toString())) : Json.createValue(d);
    }

    private static JsonValue record(Object value, int depth, List<Object> path) {
        JsonObjectBuilder object = Json.createObjectBuilder();
        for (RecordComponent component : value.getClass().getRecordComponents()) {
            McpParam info = component.getAnnotation(McpParam.class);
            String name = info != null && !info.name().isEmpty() ? info.name() : component.getName();
            Object member;
            try {
                var accessor = component.getAccessor();
                accessor.setAccessible(true);
                member = accessor.invoke(value);
            } catch (InvocationTargetException | IllegalAccessException e) {
                throw new IllegalStateException("Cannot read record component " + name, e);
            }
            if (member instanceof Optional<?> optional && optional.isEmpty()) {
                continue;
            }
            object.add(name, toJson(member, depth + 1, path));
        }
        return object.build();
    }
}
