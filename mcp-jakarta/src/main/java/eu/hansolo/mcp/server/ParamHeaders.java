package eu.hansolo.mcp.server;

import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The {@code x-mcp-header} annotation of a tool's {@code inputSchema} and the {@code Mcp-Param-*} request headers it asks for.
 * <p>
 * A property annotated with {@code "x-mcp-header": "Region"} is mirrored by the client into a {@code Mcp-Param-Region} header, so that
 * gateways can route on it without parsing the body. The server must reject a request whose header and body disagree.
 */
final class ParamHeaders {

    static final String ANNOTATION = "x-mcp-header";
    static final String PREFIX     = "Mcp-Param-";

    private static final Pattern INTEGER          = Pattern.compile("-?\\d+");
    private static final long    MAX_SAFE_INTEGER = 9007199254740991L;
    private static final String  TCHAR_SPECIALS   = "!#$%&'*+-.^_`|~";
    /** Keywords whose value is data, not a schema: an annotation inside them is not an annotation. */
    private static final Set<String> DATA_KEYWORDS = Set.of("const", "default", "enum", "examples");


    private ParamHeaders() {
    }


    /** A property that is mirrored into a header. {@code path} is the chain of property names from the root. */
    record Param(List<String> path, String name, String type) {

        String headerName() {
            return PREFIX + name;
        }
    }


    /**
     * Finds the annotated properties of a schema.
     *
     * @throws IllegalArgumentException if the annotation is used where the specification does not allow it
     */
    static List<Param> declared(JsonObject schema) {
        List<Param> found = new ArrayList<>();
        scan(schema, List.of(), true, true, found);
        Set<String> seen = new java.util.HashSet<>();
        for (Param param : found) {
            if (!seen.add(param.name().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("x-mcp-header '" + param.name() + "' is used twice (names are compared case-insensitively)");
            }
        }
        return found;
    }

    private static void scan(JsonObject schema, List<String> path, boolean reachable, boolean root, List<Param> found) {
        JsonValue annotation = schema.get(ANNOTATION);
        if (annotation != null) {
            if (root || !reachable) {
                throw new IllegalArgumentException("x-mcp-header at " + where(path, root) + " is not allowed: only a property reachable through 'properties' alone may carry it");
            }
            found.add(new Param(path, checkName(annotation, path), checkType(schema, path)));
        }
        for (var entry : schema.entrySet()) {
            String key = entry.getKey();
            JsonValue value = entry.getValue();
            if (key.equals(ANNOTATION) || DATA_KEYWORDS.contains(key)) {
                continue;
            }
            if (key.equals("properties") && reachable && value instanceof JsonObject properties) {
                for (var property : properties.entrySet()) {
                    if (property.getValue() instanceof JsonObject child) {
                        List<String> next = new ArrayList<>(path);
                        next.add(property.getKey());
                        scan(child, next, true, false, found);
                    }
                }
            } else {
                scanOther(value, found);
            }
        }
    }

    /** Everything below a keyword that is not a plain 'properties' chain: no annotation may appear in it. */
    private static void scanOther(JsonValue value, List<Param> found) {
        if (value instanceof JsonObject object) {
            for (var entry : object.entrySet()) {
                if (DATA_KEYWORDS.contains(entry.getKey())) {
                    continue;
                }
                if (entry.getKey().equals(ANNOTATION) && !(entry.getValue() instanceof JsonObject)) {
                    throw new IllegalArgumentException("x-mcp-header is not allowed inside items, composition keywords, conditionals or $defs");
                }
                scanOther(entry.getValue(), found);
            }
        } else if (value instanceof jakarta.json.JsonArray array) {
            array.forEach(item -> scanOther(item, found));
        }
    }

    private static String where(List<String> path, boolean root) {
        return root ? "the schema root" : "'" + String.join(".", path) + "'";
    }

    private static String checkName(JsonValue annotation, List<String> path) {
        if (!(annotation instanceof JsonString text) || text.getString().isEmpty()) {
            throw new IllegalArgumentException("x-mcp-header on '" + String.join(".", path) + "' must be a non-empty string");
        }
        String name = text.getString();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean tchar = c < 128 && (Character.isLetterOrDigit(c) || TCHAR_SPECIALS.indexOf(c) >= 0);
            if (!tchar) {
                throw new IllegalArgumentException("x-mcp-header '" + name + "' on '" + String.join(".", path) + "' must be an HTTP token (letters, digits and !#$%&'*+-.^_`|~)");
            }
        }
        return name;
    }

    private static String checkType(JsonObject schema, List<String> path) {
        if (schema.get("type") instanceof JsonString type && Set.of("string", "integer", "boolean").contains(type.getString())) {
            return type.getString();
        }
        throw new IllegalArgumentException("x-mcp-header on '" + String.join(".", path) + "' needs type string, integer or boolean (number is not allowed)");
    }


    /**
     * Compares the {@code Mcp-Param-*} headers with the arguments.
     *
     * @throws eu.hansolo.mcp.protocol.McpProtocolException {@code HeaderMismatch} (HTTP 400) on any difference
     */
    static void check(List<Param> params, JsonObject arguments, Function<String, String> headers) {
        for (Param param : params) {
            String header = headers.apply(param.headerName());
            JsonValue value = valueAt(arguments, param.path());
            if (value == null || value == JsonValue.NULL) {
                if (header != null) {
                    decodeHeader(param, header);
                }
                continue;
            }
            if (header == null) {
                throw mismatch("Missing " + param.headerName() + " header for argument '" + String.join(".", param.path()) + "'");
            }
            String decoded = decodeHeader(param, header);
            if (!matches(param.type(), value, decoded)) {
                throw mismatch(param.headerName() + " header does not match argument '" + String.join(".", param.path()) + "'");
            }
        }
    }

    private static JsonValue valueAt(JsonObject arguments, List<String> path) {
        JsonValue current = arguments;
        for (String step : path) {
            if (!(current instanceof JsonObject object)) {
                return null;
            }
            current = object.get(step);
        }
        return current;
    }

    private static String decodeHeader(Param param, String header) {
        try {
            String decoded = HeaderValues.decode(header);
            if (decoded == header) {
                for (int i = 0; i < header.length(); i++) {
                    char c = header.charAt(i);
                    if (c < 0x20 || c > 0x7E) {
                        throw mismatch(param.headerName() + " header contains characters that must be sent as =?base64?...?=");
                    }
                }
            }
            return decoded;
        } catch (IllegalArgumentException e) {
            throw mismatch(param.headerName() + " header is not valid base64");
        }
    }

    private static boolean matches(String type, JsonValue value, String header) {
        switch (type) {
            case "string":
                return !(value instanceof JsonString text) || text.getString().equals(header);
            case "boolean":
                if (value.getValueType() != JsonValue.ValueType.TRUE && value.getValueType() != JsonValue.ValueType.FALSE) {
                    return true; // wrong type: the schema check reports it
                }
                return header.equals(String.valueOf(value.getValueType() == JsonValue.ValueType.TRUE));
            default:
                if (!(value instanceof JsonNumber number)) {
                    return true;
                }
                if (!INTEGER.matcher(header).matches()) {
                    return false;
                }
                BigDecimal bodyValue = number.bigDecimalValue();
                if (bodyValue.abs().compareTo(BigDecimal.valueOf(MAX_SAFE_INTEGER)) > 0) {
                    return false;
                }
                return bodyValue.compareTo(new BigDecimal(header)) == 0;
        }
    }

    private static eu.hansolo.mcp.protocol.McpProtocolException mismatch(String message) {
        return eu.hansolo.mcp.protocol.McpProtocolException.headerMismatch("Header mismatch: " + message);
    }
}
