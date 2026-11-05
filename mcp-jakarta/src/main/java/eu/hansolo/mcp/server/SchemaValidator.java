package eu.hansolo.mcp.server;

import jakarta.json.JsonArray;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import java.util.Optional;

/**
 * A deliberately small check of tool arguments against the top level of the tool's inputSchema: {@code required},
 * {@code properties[*].type} and {@code additionalProperties: false}. It is NOT a JSON Schema 2020-12 validator, so tools
 * must still validate values (ranges, formats, nested objects) themselves.
 */
final class SchemaValidator {

    private SchemaValidator() {
    }


    /** Returns an error message, or empty if the arguments pass the checks above. */
    static Optional<String> validate(JsonObject schema, JsonObject arguments) {
        if (schema.get("required") instanceof JsonArray required) {
            for (JsonValue name : required) {
                if (name instanceof JsonString s && !arguments.containsKey(s.getString())) {
                    return Optional.of("Missing required property '" + s.getString() + "'");
                }
            }
        }
        JsonObject properties = schema.get("properties") instanceof JsonObject p ? p : JsonObject.EMPTY_JSON_OBJECT;
        for (var entry : arguments.entrySet()) {
            JsonValue propertySchema = properties.get(entry.getKey());
            if (propertySchema == null) {
                if (schema.get("additionalProperties") == JsonValue.FALSE) {
                    return Optional.of("Unexpected property '" + entry.getKey() + "'");
                }
                continue;
            }
            if (propertySchema instanceof JsonObject ps && ps.containsKey("type") && !matchesType(ps.get("type"), entry.getValue())) {
                return Optional.of("Property '" + entry.getKey() + "' must be of type " + ps.get("type"));
            }
        }
        return Optional.empty();
    }

    private static boolean matchesType(JsonValue type, JsonValue value) {
        if (type instanceof JsonString s) {
            return matches(s.getString(), value);
        }
        if (type instanceof JsonArray types) {
            return types.stream().anyMatch(t -> t instanceof JsonString s && matches(s.getString(), value));
        }
        return true;
    }

    private static boolean matches(String type, JsonValue value) {
        return switch (type) {
            case "string" -> value.getValueType() == JsonValue.ValueType.STRING;
            case "number" -> value.getValueType() == JsonValue.ValueType.NUMBER;
            case "integer" -> value instanceof JsonNumber n && n.bigDecimalValue().stripTrailingZeros().scale() <= 0;
            case "boolean" -> value.getValueType() == JsonValue.ValueType.TRUE || value.getValueType() == JsonValue.ValueType.FALSE;
            case "object" -> value.getValueType() == JsonValue.ValueType.OBJECT;
            case "array" -> value.getValueType() == JsonValue.ValueType.ARRAY;
            case "null" -> value.getValueType() == JsonValue.ValueType.NULL;
            default -> true;
        };
    }
}
