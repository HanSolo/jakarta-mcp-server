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
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/** The reverse of {@link JsonConverter}: Java values returned by tools to JSON. An empty {@code Optional} record component is omitted. */
public final class JsonMapper {

    private static final int MAX_DEPTH = 32;


    private JsonMapper() {
    }


    public static JsonValue toJson(Object value) {
        return toJson(value, 0);
    }

    private static JsonValue toJson(Object value, int depth) {
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
            return Json.createValue(((Number) value).doubleValue());
        }
        if (value instanceof Enum<?> constant) {
            return Json.createValue(constant.name());
        }
        if (value instanceof Optional<?> optional) {
            return optional.isPresent() ? toJson(optional.get(), depth + 1) : JsonValue.NULL;
        }
        if (value instanceof Map<?, ?> map) {
            JsonObjectBuilder object = Json.createObjectBuilder();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                object.add(String.valueOf(entry.getKey()), toJson(entry.getValue(), depth + 1));
            }
            return object.build();
        }
        if (value instanceof Collection<?> collection) {
            JsonArrayBuilder array = Json.createArrayBuilder();
            collection.forEach(element -> array.add(toJson(element, depth + 1)));
            return array.build();
        }
        if (value.getClass().isArray()) {
            JsonArrayBuilder array = Json.createArrayBuilder();
            for (int i = 0; i < java.lang.reflect.Array.getLength(value); i++) {
                array.add(toJson(java.lang.reflect.Array.get(value, i), depth + 1));
            }
            return array.build();
        }
        if (value.getClass().isRecord()) {
            return record(value, depth);
        }
        throw new IllegalStateException("Cannot convert " + value.getClass().getName() + " to JSON: return a String, a number, a record, a Map, a collection, or a JSON-P value");
    }

    private static JsonValue record(Object value, int depth) {
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
            object.add(name, toJson(member, depth + 1));
        }
        return object.build();
    }
}
