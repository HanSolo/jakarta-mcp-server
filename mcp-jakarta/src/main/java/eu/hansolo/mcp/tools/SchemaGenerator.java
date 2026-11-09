package eu.hansolo.mcp.tools;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonValue;
import eu.hansolo.mcp.api.McpParam;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Derives JSON Schema (2020-12 vocabulary, no {@code $ref}) from Java types. Fails with a clear message for types it cannot represent. */
public final class SchemaGenerator {

    private SchemaGenerator() {
    }


    /** The schema of one value of this type; {@code Optional<T>} is the schema of T. */
    public static JsonObject schemaFor(Type type) {
        return schema(type, new HashSet<>());
    }

    private static JsonObject schema(Type type, Set<Class<?>> path) {
        Class<?> raw = rawClass(type);
        if (raw == Optional.class) {
            return schema(typeArgument(type, 0), path);
        }
        if (raw == String.class) {
            return object("string");
        }
        if (raw == boolean.class || raw == Boolean.class) {
            return object("boolean");
        }
        if (isInteger(raw)) {
            return object("integer");
        }
        if (isDecimal(raw)) {
            return object("number");
        }
        if (raw.isEnum()) {
            JsonArrayBuilder values = Json.createArrayBuilder();
            for (Object constant : raw.getEnumConstants()) {
                values.add(((Enum<?>) constant).name());
            }
            return Json.createObjectBuilder().add("type", "string").add("enum", values).build();
        }
        if (raw == JsonObject.class) {
            return object("object");
        }
        if (raw == JsonArray.class) {
            return object("array");
        }
        if (raw == JsonValue.class) {
            return JsonObject.EMPTY_JSON_OBJECT;
        }
        if (raw.isArray()) {
            Type component = type instanceof GenericArrayType array ? array.getGenericComponentType() : raw.getComponentType();
            return Json.createObjectBuilder().add("type", "array").add("items", schema(component, path)).build();
        }
        if (raw == List.class || raw == Collection.class || raw == Set.class) {
            JsonObjectBuilder array = Json.createObjectBuilder().add("type", "array").add("items", schema(typeArgument(type, 0), path));
            return (raw == Set.class ? array.add("uniqueItems", true) : array).build();
        }
        if (raw == Map.class) {
            if (typeArgument(type, 0) != String.class) {
                throw unsupported(type, "map keys must be String");
            }
            return Json.createObjectBuilder().add("type", "object").add("additionalProperties", schema(typeArgument(type, 1), path)).build();
        }
        if (raw.isRecord()) {
            return recordSchema(raw, path);
        }
        throw unsupported(type, "supported are scalars, enums, List/Set/Collection/arrays, Map<String,V>, records, Optional and the JSON-P types");
    }

    private static JsonObject recordSchema(Class<?> record, Set<Class<?>> path) {
        if (!path.add(record)) {
            throw unsupported(record, "recursive record types cannot be described without $ref");
        }
        JsonObjectBuilder properties = Json.createObjectBuilder();
        JsonArrayBuilder required = Json.createArrayBuilder();
        boolean anyRequired = false;
        for (RecordComponent component : record.getRecordComponents()) {
            McpParam info = component.getAnnotation(McpParam.class);
            String name = info != null && !info.name().isEmpty() ? info.name() : component.getName();
            properties.add(name, withHeader(withDescription(schema(component.getGenericType(), path), info == null ? "" : info.description()), info == null ? "" : info.header()));
            if (isRequired(component.getGenericType(), info)) {
                required.add(name);
                anyRequired = true;
            }
        }
        path.remove(record);
        JsonObjectBuilder schema = Json.createObjectBuilder().add("type", "object").add("properties", properties);
        if (anyRequired) {
            schema.add("required", required);
        }
        return schema.add("additionalProperties", false).build();
    }

    /** Adds a description to an existing schema object. */
    public static JsonObject withDescription(JsonObject schema, String description) {
        return description == null || description.isEmpty() ? schema : Json.createObjectBuilder(schema).add("description", description).build();
    }

    /** Adds the {@code x-mcp-header} annotation to an existing schema object. */
    public static JsonObject withHeader(JsonObject schema, String header) {
        return header == null || header.isEmpty() ? schema : Json.createObjectBuilder(schema).add("x-mcp-header", header).build();
    }

    /** Required unless it is an {@code Optional}, or an object type explicitly marked {@code required = false}. */
    public static boolean isRequired(Type type, McpParam info) {
        Class<?> raw = rawClass(type);
        if (raw == Optional.class) {
            return false;
        }
        if (raw.isPrimitive()) {
            if (info != null && !info.required()) {
                throw unsupported(type, "a primitive cannot be optional; use the wrapper type or Optional");
            }
            return true;
        }
        return info == null || info.required();
    }

    static Class<?> rawClass(Type type) {
        if (type instanceof Class<?> c) {
            return c;
        }
        if (type instanceof ParameterizedType p) {
            return (Class<?>) p.getRawType();
        }
        if (type instanceof GenericArrayType array) {
            return java.lang.reflect.Array.newInstance(rawClass(array.getGenericComponentType()), 0).getClass();
        }
        throw unsupported(type, "type variables and wildcards are not supported");
    }

    static Type typeArgument(Type type, int index) {
        if (type instanceof ParameterizedType p && p.getActualTypeArguments().length > index) {
            return p.getActualTypeArguments()[index];
        }
        throw unsupported(type, "raw generic types are not supported; give the type argument");
    }

    static boolean isInteger(Class<?> c) {
        return c == int.class || c == Integer.class || c == long.class || c == Long.class || c == short.class || c == Short.class || c == byte.class || c == Byte.class || c == BigInteger.class;
    }

    static boolean isDecimal(Class<?> c) {
        return c == double.class || c == Double.class || c == float.class || c == Float.class || c == BigDecimal.class;
    }

    private static JsonObject object(String type) {
        return Json.createObjectBuilder().add("type", type).build();
    }

    static IllegalStateException unsupported(Type type, String why) {
        return new IllegalStateException("Unsupported MCP tool type " + type.getTypeName() + ": " + why);
    }
}
