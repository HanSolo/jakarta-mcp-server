package eu.hansolo.mcp.server;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Checks tool arguments against the tool's {@code inputSchema}, with the assertion keywords of JSON Schema 2020-12 that tool
 * schemas use in practice:
 *
 * <ul>
 *   <li>any type: {@code type}, {@code enum}, {@code const}, {@code allOf}, {@code anyOf}, {@code oneOf}, {@code not}, {@code if}/{@code then}/{@code else}, {@code $ref}</li>
 *   <li>numbers: {@code minimum}, {@code maximum}, {@code exclusiveMinimum}, {@code exclusiveMaximum}, {@code multipleOf}</li>
 *   <li>strings: {@code minLength}, {@code maxLength}, {@code pattern}, and {@code format} when format assertion is switched on (see {@link Formats})</li>
 *   <li>arrays: {@code items}, {@code prefixItems}, {@code minItems}, {@code maxItems}, {@code uniqueItems}, {@code contains}, {@code minContains}, {@code maxContains}, {@code unevaluatedItems}</li>
 *   <li>objects: {@code properties}, {@code required}, {@code additionalProperties}, {@code patternProperties}, {@code propertyNames}, {@code minProperties}, {@code maxProperties}, {@code dependentRequired}, {@code dependentSchemas}, {@code unevaluatedProperties}</li>
 * </ul>
 *
 * <p>{@code unevaluatedProperties} and {@code unevaluatedItems} count what the keywords next to them and the schemas applied in
 * place ({@code $ref}, {@code allOf}, the {@code anyOf}/{@code oneOf} branches that match, {@code if}/{@code then}/{@code else},
 * {@code dependentSchemas}) have already evaluated. {@code format} is an annotation unless assertion is switched on, which is the
 * default of JSON Schema 2020-12. Not asserted: anything else unknown. {@code $ref} is resolved inside the schema only ({@code #}, JSON pointers such as {@code #/$defs/address} and
 * {@code $anchor} names); a reference that cannot be resolved there, such as a URL, is ignored and never fetched. {@code pattern}
 * uses {@code java.util.regex} and matches anywhere in the string, as JSON Schema does; one that does not compile is ignored. A tool
 * must still check what a schema cannot say.
 *
 * <p>The first problem found is reported, without echoing any value the client sent (property names are shortened).
 */
final class SchemaValidator {

    private static final int MAX_DEPTH = 100;
    private static final int MAX_NAME  = 64;

    private final JsonObject root;
    private final boolean    formats;


    private SchemaValidator(JsonObject root, boolean formats) {
        this.root    = root;
        this.formats = formats;
    }


    /** Returns the first problem, or empty if the arguments satisfy the schema. {@code format} is not asserted. */
    static Optional<String> validate(JsonObject schema, JsonObject arguments) {
        return validate(schema, arguments, false);
    }

    /** Returns the first problem, or empty if the arguments satisfy the schema; with {@code formats}, {@code format} is asserted too. */
    static Optional<String> validate(JsonObject schema, JsonObject arguments, boolean formats) {
        return Optional.ofNullable(new SchemaValidator(schema, formats).check(schema, arguments, "", 0));
    }

    // ---- one schema against one instance ---------------------------------------------------------------------------

    /** @return a description of the first problem, or null if the instance is valid */
    private String check(JsonValue schema, JsonValue instance, String path, int depth) {
        if (schema == JsonValue.TRUE) {
            return null;
        }
        if (schema == JsonValue.FALSE) {
            return where(path) + " is not allowed";
        }
        if (!(schema instanceof JsonObject s)) {
            return null;
        }
        if (depth > MAX_DEPTH) {
            return where(path) + " is nested too deeply to check";
        }
        String problem = reference(s, instance, path, depth);
        if (problem == null) {
            problem = generic(s, instance, path, depth);
        }
        if (problem == null) {
            problem = switch (instance.getValueType()) {
                case NUMBER -> number(s, (JsonNumber) instance, path);
                case STRING -> string(s, (JsonString) instance, path);
                case ARRAY  -> array(s, (JsonArray) instance, path, depth);
                case OBJECT -> object(s, (JsonObject) instance, path, depth);
                default     -> null;
            };
        }
        if (problem == null) {
            problem = unevaluated(s, instance, path, depth);
        }
        return problem;
    }

    private String reference(JsonObject s, JsonValue instance, String path, int depth) {
        if (s.get("$ref") instanceof JsonString ref) {
            JsonValue target = resolve(ref.getString());
            if (target != null) {
                return check(target, instance, path, depth + 1);
            }
        }
        return null;
    }

    private String generic(JsonObject s, JsonValue instance, String path, int depth) {
        JsonValue type = s.get("type");
        if (type != null && !typeMatches(type, instance)) {
            return where(path) + " must be of type " + typeNames(type);
        }
        if (s.get("enum") instanceof JsonArray options && options.stream().noneMatch(o -> same(o, instance))) {
            return where(path) + " must be one of " + options;
        }
        if (s.containsKey("const") && !same(s.get("const"), instance)) {
            return where(path) + " must equal " + s.get("const");
        }
        if (s.get("allOf") instanceof JsonArray all) {
            for (JsonValue sub : all) {
                String problem = check(sub, instance, path, depth + 1);
                if (problem != null) {
                    return problem;
                }
            }
        }
        if (s.get("anyOf") instanceof JsonArray any && !any.isEmpty() && any.stream().noneMatch(sub -> check(sub, instance, path, depth + 1) == null)) {
            return where(path) + " does not match any of the allowed shapes (anyOf)";
        }
        if (s.get("oneOf") instanceof JsonArray one) {
            long matches = one.stream().filter(sub -> check(sub, instance, path, depth + 1) == null).count();
            if (matches != 1) {
                return where(path) + (matches == 0 ? " does not match any of the allowed shapes (oneOf)" : " matches more than one of the shapes of oneOf");
            }
        }
        if (s.containsKey("not") && check(s.get("not"), instance, path, depth + 1) == null) {
            return where(path) + " matches a shape that is not allowed";
        }
        if (s.containsKey("if")) {
            boolean applies = check(s.get("if"), instance, path, depth + 1) == null;
            if (applies && s.containsKey("then")) {
                return check(s.get("then"), instance, path, depth + 1);
            }
            if (!applies && s.containsKey("else")) {
                return check(s.get("else"), instance, path, depth + 1);
            }
        }
        return null;
    }

    private String number(JsonObject s, JsonNumber value, String path) {
        BigDecimal n = value.bigDecimalValue();
        if (s.get("minimum") instanceof JsonNumber min && n.compareTo(min.bigDecimalValue()) < 0) {
            return where(path) + " must be at least " + min.bigDecimalValue().toPlainString();
        }
        if (s.get("maximum") instanceof JsonNumber max && n.compareTo(max.bigDecimalValue()) > 0) {
            return where(path) + " must be at most " + max.bigDecimalValue().toPlainString();
        }
        if (s.get("exclusiveMinimum") instanceof JsonNumber min && n.compareTo(min.bigDecimalValue()) <= 0) {
            return where(path) + " must be greater than " + min.bigDecimalValue().toPlainString();
        }
        if (s.get("exclusiveMaximum") instanceof JsonNumber max && n.compareTo(max.bigDecimalValue()) >= 0) {
            return where(path) + " must be less than " + max.bigDecimalValue().toPlainString();
        }
        if (s.get("multipleOf") instanceof JsonNumber step && step.bigDecimalValue().signum() > 0) {
            try {
                if (n.remainder(step.bigDecimalValue()).signum() != 0) {
                    return where(path) + " must be a multiple of " + step.bigDecimalValue().toPlainString();
                }
            } catch (ArithmeticException e) {
                return where(path) + " cannot be checked against multipleOf";
            }
        }
        return null;
    }

    private String string(JsonObject s, JsonString value, String path) {
        String text = value.getString();
        int length = text.codePointCount(0, text.length());
        if (s.get("minLength") instanceof JsonNumber min && length < min.intValue()) {
            return where(path) + " must be at least " + min.intValue() + " characters long";
        }
        if (s.get("maxLength") instanceof JsonNumber max && length > max.intValue()) {
            return where(path) + " must be at most " + max.intValue() + " characters long";
        }
        if (s.get("pattern") instanceof JsonString pattern) {
            Pattern compiled = compile(pattern.getString());
            if (compiled != null && !compiled.matcher(text).find()) {
                return where(path) + " must match the pattern " + pattern.getString();
            }
        }
        if (formats && s.get("format") instanceof JsonString format && !Formats.valid(format.getString(), text)) {
            return where(path) + " must be a valid " + shorten(format.getString());
        }
        return null;
    }

    private String array(JsonObject s, JsonArray items, String path, int depth) {
        if (s.get("minItems") instanceof JsonNumber min && items.size() < min.intValue()) {
            return where(path) + " must have at least " + min.intValue() + " items";
        }
        if (s.get("maxItems") instanceof JsonNumber max && items.size() > max.intValue()) {
            return where(path) + " must have at most " + max.intValue() + " items";
        }
        if (s.get("uniqueItems") == JsonValue.TRUE) {
            for (int i = 0; i < items.size(); i++) {
                for (int j = i + 1; j < items.size(); j++) {
                    if (same(items.get(i), items.get(j))) {
                        return where(path) + " must not contain the same item twice";
                    }
                }
            }
        }
        int prefix = 0;
        JsonValue itemSchema = s.get("items");
        if (s.get("prefixItems") instanceof JsonArray positional) {
            prefix = positional.size();
        } else if (itemSchema instanceof JsonArray positional) {   // draft-07 tuple form
            prefix = positional.size();
            itemSchema = s.get("additionalItems");
            for (int i = 0; i < Math.min(prefix, items.size()); i++) {
                String problem = check(positional.get(i), items.get(i), index(path, i), depth + 1);
                if (problem != null) {
                    return problem;
                }
            }
        }
        if (s.get("prefixItems") instanceof JsonArray positional) {
            for (int i = 0; i < Math.min(prefix, items.size()); i++) {
                String problem = check(positional.get(i), items.get(i), index(path, i), depth + 1);
                if (problem != null) {
                    return problem;
                }
            }
        }
        if (itemSchema != null && !(itemSchema instanceof JsonArray)) {
            for (int i = prefix; i < items.size(); i++) {
                String problem = check(itemSchema, items.get(i), index(path, i), depth + 1);
                if (problem != null) {
                    return problem;
                }
            }
        }
        if (s.containsKey("contains")) {
            long matching = 0;
            for (int i = 0; i < items.size(); i++) {
                if (check(s.get("contains"), items.get(i), index(path, i), depth + 1) == null) {
                    matching++;
                }
            }
            long min = s.get("minContains") instanceof JsonNumber m ? m.longValue() : 1;
            if (matching < min) {
                return where(path) + " must contain at least " + min + " matching " + (min == 1 ? "item" : "items");
            }
            if (s.get("maxContains") instanceof JsonNumber max && matching > max.longValue()) {
                return where(path) + " must contain at most " + max.longValue() + " matching items";
            }
        }
        return null;
    }

    private String object(JsonObject s, JsonObject value, String path, int depth) {
        if (s.get("required") instanceof JsonArray required) {
            for (JsonValue name : required) {
                if (name instanceof JsonString key && !value.containsKey(key.getString())) {
                    return "Missing required property '" + member(path, key.getString()) + "'";
                }
            }
        }
        if (s.get("minProperties") instanceof JsonNumber min && value.size() < min.intValue()) {
            return where(path) + " must have at least " + min.intValue() + " properties";
        }
        if (s.get("maxProperties") instanceof JsonNumber max && value.size() > max.intValue()) {
            return where(path) + " must have at most " + max.intValue() + " properties";
        }
        if (s.get("dependentRequired") instanceof JsonObject dependent) {
            for (var entry : dependent.entrySet()) {
                if (value.containsKey(entry.getKey()) && entry.getValue() instanceof JsonArray needed) {
                    for (JsonValue name : needed) {
                        if (name instanceof JsonString key && !value.containsKey(key.getString())) {
                            return "Missing required property '" + member(path, key.getString()) + "' (needed when '" + member(path, entry.getKey()) + "' is present)";
                        }
                    }
                }
            }
        }
        if (s.get("dependentSchemas") instanceof JsonObject dependent) {
            for (var entry : dependent.entrySet()) {
                if (value.containsKey(entry.getKey())) {
                    String problem = check(entry.getValue(), value, path, depth + 1);
                    if (problem != null) {
                        return problem;
                    }
                }
            }
        }
        JsonObject properties = s.get("properties") instanceof JsonObject p ? p : JsonObject.EMPTY_JSON_OBJECT;
        JsonObject patterns   = s.get("patternProperties") instanceof JsonObject p ? p : JsonObject.EMPTY_JSON_OBJECT;
        for (var entry : value.entrySet()) {
            String key = entry.getKey();
            String at  = member(path, key);
            if (s.containsKey("propertyNames")) {
                String problem = check(s.get("propertyNames"), Json.createValue(key), at, depth + 1);
                if (problem != null) {
                    return "Property name '" + shorten(at) + "' is not allowed";
                }
            }
            boolean known = false;
            if (properties.containsKey(key)) {
                known = true;
                String problem = check(properties.get(key), entry.getValue(), at, depth + 1);
                if (problem != null) {
                    return problem;
                }
            }
            for (var pattern : patterns.entrySet()) {
                Pattern compiled = compile(pattern.getKey());
                if (compiled != null && compiled.matcher(key).find()) {
                    known = true;
                    String problem = check(pattern.getValue(), entry.getValue(), at, depth + 1);
                    if (problem != null) {
                        return problem;
                    }
                }
            }
            if (!known && s.containsKey("additionalProperties")) {
                JsonValue additional = s.get("additionalProperties");
                if (additional == JsonValue.FALSE) {
                    return "Unexpected property '" + shorten(at) + "'";
                }
                String problem = check(additional, entry.getValue(), at, depth + 1);
                if (problem != null) {
                    return problem;
                }
            }
        }
        return null;
    }

    // ---- unevaluatedProperties and unevaluatedItems ------------------------------------------------------------------

    /** Runs when everything else about the instance has been found valid, so the evaluated parts are known. */
    private String unevaluated(JsonObject s, JsonValue instance, String path, int depth) {
        if (instance instanceof JsonObject object && s.containsKey("unevaluatedProperties")) {
            JsonValue rule = s.get("unevaluatedProperties");
            Set<String> done = evaluatedProperties(s, object, depth, true);
            for (var entry : object.entrySet()) {
                if (done.contains(entry.getKey())) {
                    continue;
                }
                String at = member(path, entry.getKey());
                if (rule == JsonValue.FALSE) {
                    return "Unexpected property '" + shorten(at) + "'";
                }
                String problem = check(rule, entry.getValue(), at, depth + 1);
                if (problem != null) {
                    return problem;
                }
            }
        }
        if (instance instanceof JsonArray array && s.containsKey("unevaluatedItems")) {
            JsonValue rule = s.get("unevaluatedItems");
            BitSet done = evaluatedItems(s, array, depth, true);
            for (int i = 0; i < array.size(); i++) {
                if (done.get(i)) {
                    continue;
                }
                if (rule == JsonValue.FALSE) {
                    return where(index(path, i)) + " is not allowed";
                }
                String problem = check(rule, array.get(i), index(path, i), depth + 1);
                if (problem != null) {
                    return problem;
                }
            }
        }
        return null;
    }

    /** {@code own}: s is the schema whose unevaluatedProperties is being applied, so that keyword evaluates nothing yet. */
    private Set<String> evaluatedProperties(JsonObject s, JsonObject value, int depth, boolean own) {
        Set<String> done = new HashSet<>();
        if (depth > MAX_DEPTH) {
            return done;
        }
        JsonObject properties = s.get("properties") instanceof JsonObject p ? p : JsonObject.EMPTY_JSON_OBJECT;
        JsonObject patterns   = s.get("patternProperties") instanceof JsonObject p ? p : JsonObject.EMPTY_JSON_OBJECT;
        for (String key : value.keySet()) {
            if (properties.containsKey(key)) {
                done.add(key);
            }
            for (String pattern : patterns.keySet()) {
                Pattern compiled = compile(pattern);
                if (compiled != null && compiled.matcher(key).find()) {
                    done.add(key);
                }
            }
        }
        if (s.containsKey("additionalProperties") || (!own && s.containsKey("unevaluatedProperties"))) {
            done.addAll(value.keySet());
        }
        for (JsonObject applied : applied(s, value, depth)) {
            done.addAll(evaluatedProperties(applied, value, depth + 1, false));
        }
        return done;
    }

    /** {@code own}: as for {@link #evaluatedProperties}. */
    private BitSet evaluatedItems(JsonObject s, JsonArray items, int depth, boolean own) {
        BitSet done = new BitSet(items.size());
        if (depth > MAX_DEPTH) {
            return done;
        }
        if (s.get("prefixItems") instanceof JsonArray positional) {
            done.set(0, Math.min(positional.size(), items.size()));
        }
        if (s.get("items") instanceof JsonArray positional) {   // draft-07 tuple form
            done.set(0, Math.min(positional.size(), items.size()));
            if (s.containsKey("additionalItems")) {
                done.set(0, items.size());
            }
        } else if (s.containsKey("items")) {
            done.set(0, items.size());
        }
        if (s.containsKey("contains")) {
            for (int i = 0; i < items.size(); i++) {
                if (check(s.get("contains"), items.get(i), index("", i), depth + 1) == null) {
                    done.set(i);
                }
            }
        }
        if (!own && s.containsKey("unevaluatedItems")) {
            done.set(0, items.size());
        }
        for (JsonObject applied : applied(s, items, depth)) {
            done.or(evaluatedItems(applied, items, depth + 1, false));
        }
        return done;
    }

    /** The subschemas that apply to the same instance as {@code s} does and passed: what their annotations count. */
    private List<JsonObject> applied(JsonObject s, JsonValue instance, int depth) {
        List<JsonObject> result = new ArrayList<>();
        if (s.get("$ref") instanceof JsonString ref && resolve(ref.getString()) instanceof JsonObject target) {
            result.add(target);
        }
        if (s.get("allOf") instanceof JsonArray all) {
            all.stream().filter(JsonObject.class::isInstance).map(JsonObject.class::cast).forEach(result::add);
        }
        for (String keyword : List.of("anyOf", "oneOf")) {
            if (s.get(keyword) instanceof JsonArray any) {
                for (JsonValue sub : any) {
                    if (sub instanceof JsonObject candidate && check(candidate, instance, "", depth + 1) == null) {
                        result.add(candidate);
                    }
                }
            }
        }
        if (s.get("if") instanceof JsonObject condition) {
            if (check(condition, instance, "", depth + 1) == null) {
                result.add(condition);
                if (s.get("then") instanceof JsonObject then) {
                    result.add(then);
                }
            } else if (s.get("else") instanceof JsonObject otherwise) {
                result.add(otherwise);
            }
        }
        if (instance instanceof JsonObject object && s.get("dependentSchemas") instanceof JsonObject dependent) {
            for (var entry : dependent.entrySet()) {
                if (object.containsKey(entry.getKey()) && entry.getValue() instanceof JsonObject sub) {
                    result.add(sub);
                }
            }
        }
        return result;
    }

    // ---- $ref ------------------------------------------------------------------------------------------------------

    /** The schema a local reference points to, or null if it points anywhere else. */
    private JsonValue resolve(String ref) {
        if (ref.equals("#")) {
            return root;
        }
        if (ref.startsWith("#/")) {
            JsonValue current = root;
            for (String token : ref.substring(2).split("/", -1)) {
                String key = URLDecoder.decode(token.replace("+", "%2B"), StandardCharsets.UTF_8).replace("~1", "/").replace("~0", "~");
                if (current instanceof JsonObject object && object.containsKey(key)) {
                    current = object.get(key);
                } else if (current instanceof JsonArray array && key.matches("\\d{1,9}") && Integer.parseInt(key) < array.size()) {
                    current = array.get(Integer.parseInt(key));
                } else {
                    return null;
                }
            }
            return current;
        }
        if (ref.startsWith("#") && ref.length() > 1) {
            return findAnchor(root, ref.substring(1), 0);
        }
        return null;
    }

    private static JsonValue findAnchor(JsonValue node, String anchor, int depth) {
        if (depth > MAX_DEPTH) {
            return null;
        }
        if (node instanceof JsonObject object) {
            if (object.get("$anchor") instanceof JsonString a && a.getString().equals(anchor)) {
                return object;
            }
            for (JsonValue child : object.values()) {
                JsonValue found = findAnchor(child, anchor, depth + 1);
                if (found != null) {
                    return found;
                }
            }
        } else if (node instanceof JsonArray array) {
            for (JsonValue child : array) {
                JsonValue found = findAnchor(child, anchor, depth + 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    // ---- types and equality ----------------------------------------------------------------------------------------

    private static boolean typeMatches(JsonValue type, JsonValue value) {
        if (type instanceof JsonString s) {
            return matches(s.getString(), value);
        }
        if (type instanceof JsonArray types) {
            return types.stream().anyMatch(t -> t instanceof JsonString s && matches(s.getString(), value));
        }
        return true;
    }

    private static String typeNames(JsonValue type) {
        if (type instanceof JsonString s) {
            return s.getString();
        }
        List<String> names = new ArrayList<>();
        if (type instanceof JsonArray types) {
            types.forEach(t -> names.add(t instanceof JsonString s ? s.getString() : "?"));
        }
        return String.join(" or ", names);
    }

    private static boolean matches(String type, JsonValue value) {
        return switch (type) {
            case "string"  -> value.getValueType() == JsonValue.ValueType.STRING;
            case "number"  -> value.getValueType() == JsonValue.ValueType.NUMBER;
            case "integer" -> value instanceof JsonNumber n && n.bigDecimalValue().stripTrailingZeros().scale() <= 0;
            case "boolean" -> value.getValueType() == JsonValue.ValueType.TRUE || value.getValueType() == JsonValue.ValueType.FALSE;
            case "object"  -> value.getValueType() == JsonValue.ValueType.OBJECT;
            case "array"   -> value.getValueType() == JsonValue.ValueType.ARRAY;
            case "null"    -> value.getValueType() == JsonValue.ValueType.NULL;
            default        -> true;
        };
    }

    /** JSON equality: numbers by value (1 equals 1.0), objects regardless of member order. */
    static boolean same(JsonValue a, JsonValue b) {
        if (a instanceof JsonNumber x && b instanceof JsonNumber y) {
            return x.bigDecimalValue().compareTo(y.bigDecimalValue()) == 0;
        }
        if (a instanceof JsonObject x && b instanceof JsonObject y) {
            if (!x.keySet().equals(y.keySet())) {
                return false;
            }
            return x.entrySet().stream().allMatch(e -> same(e.getValue(), y.get(e.getKey())));
        }
        if (a instanceof JsonArray x && b instanceof JsonArray y) {
            if (x.size() != y.size()) {
                return false;
            }
            for (int i = 0; i < x.size(); i++) {
                if (!same(x.get(i), y.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return a.equals(b);
    }

    private static Pattern compile(String regex) {
        try {
            return Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            return null;
        }
    }

    // ---- messages --------------------------------------------------------------------------------------------------

    private static String where(String path) {
        return path.isEmpty() ? "Arguments" : "Property '" + shorten(path) + "'";
    }

    private static String member(String path, String key) {
        return path.isEmpty() ? key : path + "." + key;
    }

    private static String index(String path, int i) {
        return path + "[" + i + "]";
    }

    /** Names come from the client; keep the message short whatever it sent. */
    private static String shorten(String text) {
        return text.length() <= MAX_NAME ? text : text.substring(0, MAX_NAME) + "...";
    }
}
