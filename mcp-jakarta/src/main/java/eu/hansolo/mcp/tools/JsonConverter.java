package eu.hansolo.mcp.tools;

import jakarta.json.JsonArray;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import eu.hansolo.mcp.api.McpParam;
import eu.hansolo.mcp.protocol.McpProtocolException;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Converts client-supplied JSON to the Java parameter types of an {@code @McpTool} method. The server only schema-checks the
 * top level of the arguments, so this class is where types, ranges and nested structure are really enforced; every problem is
 * an invalid-arguments error that names the offending path.
 */
public final class JsonConverter {

    private static final int MAX_DEPTH = 32;


    private JsonConverter() {
    }


    /** Converts the argument {@code name}; {@code value} is null when the client did not send it. */
    public static Object argument(JsonValue value, Type type, String name, boolean required) {
        if (value == null || value == JsonValue.NULL) {
            if (SchemaGenerator.rawClass(type) == Optional.class) {
                return Optional.empty();
            }
            if (required) {
                throw bad(name, "is required");
            }
            return null;
        }
        return convert(value, type, name, 0);
    }

    static Object convert(JsonValue value, Type type, String path, int depth) {
        if (depth > MAX_DEPTH) {
            throw bad(path, "is nested too deeply");
        }
        Class<?> raw = SchemaGenerator.rawClass(type);
        if (raw == Optional.class) {
            return value == JsonValue.NULL ? Optional.empty() : Optional.of(convert(value, SchemaGenerator.typeArgument(type, 0), path, depth));
        }
        if (raw == JsonValue.class) {
            return value;
        }
        if (value == JsonValue.NULL) {
            throw bad(path, "must not be null");
        }
        if (raw == String.class) {
            return string(value, path);
        }
        if (raw == boolean.class || raw == Boolean.class) {
            if (value == JsonValue.TRUE || value == JsonValue.FALSE) {
                return value == JsonValue.TRUE;
            }
            throw bad(path, "must be a boolean");
        }
        if (SchemaGenerator.isInteger(raw)) {
            return integer(value, raw, path);
        }
        if (SchemaGenerator.isDecimal(raw)) {
            return decimal(value, raw, path);
        }
        if (raw.isEnum()) {
            return enumConstant(value, raw, path);
        }
        if (raw == JsonObject.class) {
            return value instanceof JsonObject ? value : fail(path, "must be an object");
        }
        if (raw == JsonArray.class) {
            return value instanceof JsonArray ? value : fail(path, "must be an array");
        }
        if (raw.isArray()) {
            return array(value, type, raw, path, depth);
        }
        if (raw == List.class || raw == Collection.class || raw == Set.class) {
            return collection(value, type, raw, path, depth);
        }
        if (raw == Map.class) {
            return map(value, type, path, depth);
        }
        if (raw.isRecord()) {
            return record(value, raw, path, depth);
        }
        throw SchemaGenerator.unsupported(type, "no converter");
    }

    private static String string(JsonValue value, String path) {
        if (value instanceof JsonString s) {
            return s.getString();
        }
        throw bad(path, "must be a string");
    }

    private static Object integer(JsonValue value, Class<?> raw, String path) {
        if (!(value instanceof JsonNumber number) || !number.isIntegral()) {
            throw bad(path, "must be an integer");
        }
        try {
            if (raw == long.class || raw == Long.class) {
                return number.longValueExact();
            }
            if (raw == int.class || raw == Integer.class) {
                return number.intValueExact();
            }
            if (raw == BigInteger.class) {
                return number.bigIntegerValueExact();
            }
            long wide = number.longValueExact();
            if (raw == short.class || raw == Short.class) {
                if (wide < Short.MIN_VALUE || wide > Short.MAX_VALUE) {
                    throw new ArithmeticException();
                }
                return (short) wide;
            }
            if (wide < Byte.MIN_VALUE || wide > Byte.MAX_VALUE) {
                throw new ArithmeticException();
            }
            return (byte) wide;
        } catch (ArithmeticException e) {
            throw bad(path, "is out of range for " + raw.getSimpleName());
        }
    }

    private static Object decimal(JsonValue value, Class<?> raw, String path) {
        if (!(value instanceof JsonNumber number)) {
            throw bad(path, "must be a number");
        }
        if (raw == BigDecimal.class) {
            return number.bigDecimalValue();
        }
        double d = number.doubleValue();
        if (Double.isInfinite(d)) {
            throw bad(path, "is out of range");
        }
        return raw == float.class || raw == Float.class ? (Object) (float) d : (Object) d;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumConstant(JsonValue value, Class<?> raw, String path) {
        String text = string(value, path);
        for (Object constant : raw.getEnumConstants()) {
            if (((Enum) constant).name().equals(text)) {
                return constant;
            }
        }
        throw bad(path, "must be one of " + java.util.Arrays.stream(raw.getEnumConstants()).map(c -> ((Enum<?>) c).name()).toList());
    }

    private static Object array(JsonValue value, Type type, Class<?> raw, String path, int depth) {
        if (!(value instanceof JsonArray json)) {
            throw bad(path, "must be an array");
        }
        Type component = type instanceof GenericArrayType g ? g.getGenericComponentType() : raw.getComponentType();
        Object result = java.lang.reflect.Array.newInstance(raw.getComponentType(), json.size());
        for (int i = 0; i < json.size(); i++) {
            Object element = convert(json.get(i), component, path + "[" + i + "]", depth + 1);
            if (element == null && raw.getComponentType().isPrimitive()) {
                throw bad(path + "[" + i + "]", "must not be null");
            }
            java.lang.reflect.Array.set(result, i, element);
        }
        return result;
    }

    private static Object collection(JsonValue value, Type type, Class<?> raw, String path, int depth) {
        if (!(value instanceof JsonArray json)) {
            throw bad(path, "must be an array");
        }
        Type element = SchemaGenerator.typeArgument(type, 0);
        Collection<Object> result = raw == Set.class ? new LinkedHashSet<>() : new ArrayList<>();
        for (int i = 0; i < json.size(); i++) {
            result.add(convert(json.get(i), element, path + "[" + i + "]", depth + 1));
        }
        if (raw == Set.class && result.size() != json.size()) {
            throw bad(path, "must not contain duplicates");
        }
        return result;
    }

    private static Object map(JsonValue value, Type type, String path, int depth) {
        if (!(value instanceof JsonObject json)) {
            throw bad(path, "must be an object");
        }
        Type valueType = SchemaGenerator.typeArgument(type, 1);
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonValue> entry : json.entrySet()) {
            result.put(entry.getKey(), convert(entry.getValue(), valueType, path + "." + entry.getKey(), depth + 1));
        }
        return result;
    }

    private static Object record(JsonValue value, Class<?> raw, String path, int depth) {
        if (!(value instanceof JsonObject json)) {
            throw bad(path, "must be an object");
        }
        RecordComponent[] components = raw.getRecordComponents();
        Object[] args = new Object[components.length];
        Class<?>[] types = new Class<?>[components.length];
        Set<String> known = new LinkedHashSet<>();
        for (int i = 0; i < components.length; i++) {
            McpParam info = components[i].getAnnotation(McpParam.class);
            String name = info != null && !info.name().isEmpty() ? info.name() : components[i].getName();
            known.add(name);
            types[i] = components[i].getType();
            JsonValue member = json.get(name);
            boolean required = SchemaGenerator.isRequired(components[i].getGenericType(), info);
            args[i] = member == null || member == JsonValue.NULL ? missing(components[i].getGenericType(), required, path + "." + name) : convert(member, components[i].getGenericType(), path + "." + name, depth + 1);
        }
        for (String key : json.keySet()) {
            if (!known.contains(key)) {
                throw bad(path + "." + key, "is not a known property");
            }
        }
        try {
            var constructor = raw.getDeclaredConstructor(types);
            constructor.setAccessible(true);
            return constructor.newInstance(args);
        } catch (InvocationTargetException e) {
            throw bad(path, "is invalid: " + (e.getCause() == null ? "rejected" : String.valueOf(e.getCause().getMessage())));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot construct record " + raw.getName(), e);
        }
    }

    private static Object missing(Type type, boolean required, String path) {
        if (SchemaGenerator.rawClass(type) == Optional.class) {
            return Optional.empty();
        }
        if (required) {
            throw bad(path, "is required");
        }
        return null;
    }

    private static Object fail(String path, String message) {
        throw bad(path, message);
    }

    private static McpProtocolException bad(String path, String message) {
        return McpProtocolException.invalidArguments("Argument '" + path + "' " + message);
    }
}
