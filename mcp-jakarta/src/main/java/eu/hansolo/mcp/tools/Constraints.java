package eu.hansolo.mcp.tools;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonValue;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedArrayType;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.ParameterizedType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bean Validation constraints ({@code jakarta.validation.constraints}) on a tool parameter or a record component, written into its
 * schema as JSON Schema keywords. The model sees them, and the argument validation that runs before every tool call enforces them
 * and reports the path of the offending value. The annotations are found by name, so Bean Validation need not be on the class path.
 *
 * <p>Mapping: {@code NotEmpty}, {@code NotBlank} and {@code Size} become {@code minLength}/{@code maxLength} on text,
 * {@code minItems}/{@code maxItems} on lists, sets and arrays and {@code minProperties}/{@code maxProperties} on maps;
 * {@code Min}, {@code Max}, {@code DecimalMin}, {@code DecimalMax}, {@code Positive}, {@code PositiveOrZero}, {@code Negative} and
 * {@code NegativeOrZero} become {@code minimum}, {@code maximum}, {@code exclusiveMinimum} and {@code exclusiveMaximum};
 * {@code Pattern} becomes {@code pattern} (anchored, because Bean Validation matches the whole text) and {@code Email} the format
 * {@code email}. Constraints on the type argument ({@code List<@NotBlank String>}) apply to the items. Everything else
 * ({@code Digits}, {@code Past}, {@code AssertTrue}, custom constraints, groups) is left to the container, which validates a call
 * through a CDI bean itself.
 *
 * <p><b>Internal.</b> Not part of the stable API: used by {@link SchemaGenerator} and {@link AnnotatedToolHandler}, and it can
 * change in any release.
 */
final class Constraints {

    private static final String PACKAGE = "jakarta.validation.constraints.";


    private Constraints() {
    }


    /**
     * The schema with the constraints of the declaration.
     *
     * @param schema      the schema of the type
     * @param annotations the annotations of the parameter or record component
     * @param type        its annotated type, for the constraints on type arguments
     */
    static JsonObject apply(JsonObject schema, Annotation[] annotations, AnnotatedType type) {
        // a constraint can sit on the declaration or, as a type annotation, on the type (a record component gets only the latter)
        java.util.Set<Annotation> all = new java.util.LinkedHashSet<>(List.of(annotations));
        all.addAll(List.of(type.getAnnotations()));
        JsonObject result = own(schema, all.toArray(new Annotation[0]));
        if (type instanceof AnnotatedParameterizedType parameterized && parameterized.getType() instanceof ParameterizedType plain) {
            AnnotatedType[] arguments = parameterized.getAnnotatedActualTypeArguments();
            Class<?> raw = plain.getRawType() instanceof Class<?> c ? c : null;
            if (raw == Optional.class && arguments.length == 1) {
                return apply(result, arguments[0].getAnnotations(), arguments[0]);
            }
            if (raw != null && java.util.Collection.class.isAssignableFrom(raw) && arguments.length == 1 && result.get("items") instanceof JsonObject items) {
                return replace(result, "items", apply(items, arguments[0].getAnnotations(), arguments[0]));
            }
            if (raw == Map.class && arguments.length == 2 && result.get("additionalProperties") instanceof JsonObject values) {
                return replace(result, "additionalProperties", apply(values, arguments[1].getAnnotations(), arguments[1]));
            }
        } else if (type instanceof AnnotatedArrayType array && result.get("items") instanceof JsonObject items) {
            AnnotatedType component = array.getAnnotatedGenericComponentType();
            return replace(result, "items", apply(items, component.getAnnotations(), component));
        }
        return result;
    }

    private static JsonObject replace(JsonObject schema, String key, JsonObject value) {
        return Json.createObjectBuilder(schema).add(key, value).build();
    }


    private static JsonObject own(JsonObject schema, Annotation[] annotations) {
        String type = schema.getString("type", "");
        boolean text   = "string".equals(type);
        boolean number = "integer".equals(type) || "number".equals(type);
        boolean list   = "array".equals(type);
        boolean map    = "object".equals(type) && schema.get("additionalProperties") instanceof JsonObject;

        Map<String, JsonValue> keys     = new LinkedHashMap<>();
        List<String>           patterns = new ArrayList<>();
        for (Annotation annotation : annotations) {
            String name = annotation.annotationType().getName();
            if (!name.startsWith(PACKAGE)) {
                continue;
            }
            switch (name.substring(PACKAGE.length())) {
                case "NotEmpty" -> {
                    if (text) {
                        lengthAtLeast(keys, "minLength", 1);
                    } else if (list) {
                        lengthAtLeast(keys, "minItems", 1);
                    } else if (map) {
                        lengthAtLeast(keys, "minProperties", 1);
                    }
                }
                case "NotBlank" -> {
                    if (text) {
                        lengthAtLeast(keys, "minLength", 1);
                        patterns.add("\\S");
                    }
                }
                case "Size" -> {
                    long min = number(annotation, "min", 0);
                    long max = number(annotation, "max", Integer.MAX_VALUE);
                    String low  = text ? "minLength" : list ? "minItems" : map ? "minProperties" : null;
                    String high = text ? "maxLength" : list ? "maxItems" : map ? "maxProperties" : null;
                    if (low != null) {
                        if (min > 0) {
                            lengthAtLeast(keys, low, min);
                        }
                        if (max < Integer.MAX_VALUE) {
                            lengthAtMost(keys, high, max);
                        }
                    }
                }
                case "Min" -> {
                    if (number) {
                        keys.put("minimum", Json.createValue(number(annotation, "value", 0)));
                    }
                }
                case "Max" -> {
                    if (number) {
                        keys.put("maximum", Json.createValue(number(annotation, "value", 0)));
                    }
                }
                case "DecimalMin" -> decimal(keys, annotation, number, "minimum", "exclusiveMinimum");
                case "DecimalMax" -> decimal(keys, annotation, number, "maximum", "exclusiveMaximum");
                case "Positive" -> {
                    if (number) {
                        keys.put("exclusiveMinimum", Json.createValue(0));
                    }
                }
                case "PositiveOrZero" -> {
                    if (number) {
                        keys.put("minimum", Json.createValue(0));
                    }
                }
                case "Negative" -> {
                    if (number) {
                        keys.put("exclusiveMaximum", Json.createValue(0));
                    }
                }
                case "NegativeOrZero" -> {
                    if (number) {
                        keys.put("maximum", Json.createValue(0));
                    }
                }
                case "Pattern" -> {
                    Object regexp = attribute(annotation, "regexp");
                    Object flags  = attribute(annotation, "flags");
                    boolean noFlags = flags == null || (flags instanceof Object[] array && array.length == 0);
                    if (text && regexp instanceof String regex && !regex.isEmpty() && noFlags) {
                        patterns.add(regex);
                    }
                }
                case "Email" -> {
                    if (text && !schema.containsKey("format")) {
                        keys.put("format", Json.createValue("email"));
                    }
                }
                default -> {
                    // not expressible in JSON Schema: the container checks it, if it checks at all
                }
            }
        }
        if (keys.isEmpty() && patterns.isEmpty()) {
            return schema;
        }
        JsonObjectBuilder result = Json.createObjectBuilder(schema);
        keys.forEach(result::add);
        if (patterns.size() == 1) {
            result.add("pattern", anchored(patterns.get(0)));
        } else if (patterns.size() > 1) {
            JsonArrayBuilder all = Json.createArrayBuilder();
            patterns.forEach(p -> all.add(Json.createObjectBuilder().add("pattern", anchored(p))));
            result.add("allOf", all);
        }
        return result.build();
    }

    /** Bean Validation matches the whole text; JSON Schema finds the pattern anywhere. {@code \S} stays a search. */
    private static String anchored(String regexp) {
        return "\\S".equals(regexp) ? regexp : "^(?:" + regexp + ")$";
    }

    private static void decimal(Map<String, JsonValue> keys, Annotation annotation, boolean number, String inclusive, String exclusive) {
        if (!number || !(attribute(annotation, "value") instanceof String text)) {
            return;
        }
        try {
            BigDecimal bound = new BigDecimal(text);
            boolean inclusiveBound = !(attribute(annotation, "inclusive") instanceof Boolean b) || b;
            keys.put(inclusiveBound ? inclusive : exclusive, Json.createValue(bound));
        } catch (NumberFormatException e) {
            // an invalid bound is the container's to report
        }
    }

    private static void lengthAtLeast(Map<String, JsonValue> keys, String key, long value) {
        if (!(keys.get(key) instanceof JsonNumber old) || old.longValue() < value) {
            keys.put(key, Json.createValue(value));
        }
    }

    private static void lengthAtMost(Map<String, JsonValue> keys, String key, long value) {
        if (!(keys.get(key) instanceof JsonNumber old) || old.longValue() > value) {
            keys.put(key, Json.createValue(value));
        }
    }

    private static long number(Annotation annotation, String name, long fallback) {
        return attribute(annotation, name) instanceof Number n ? n.longValue() : fallback;
    }

    private static Object attribute(Annotation annotation, String name) {
        try {
            return annotation.annotationType().getMethod(name).invoke(annotation);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
