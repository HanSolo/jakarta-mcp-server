package eu.hansolo.mcp.tools;

import eu.hansolo.mcp.api.McpParam;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.List;

/**
 * The JSON-B annotations that decide how a property is named and whether it appears: {@code @JsonbProperty} (the name),
 * {@code @JsonbTransient} (left out of results) and {@code @JsonbPropertyOrder} (the order in a bean). They are found by name, so
 * JSON-B need not be on the class path, and JSON-B itself is never called. A DTO that already carries them is read the way its REST
 * resource writes it.
 *
 * <p>Not honoured, and ignored without an error: {@code @JsonbDateFormat} and {@code @JsonbNumberFormat} (the text of dates and
 * numbers stays ISO-8601 and plain, because the schema says so), {@code @JsonbNillable} (a {@code null} is always left out),
 * {@code @JsonbCreator}, {@code @JsonbTypeAdapter} and {@code @JsonbVisibility} (beans are results only).
 *
 * <p><b>Internal.</b> Not part of the stable API: used by {@link Beans}, {@link JsonMapper}, {@link JsonConverter} and
 * {@link SchemaGenerator}, and it can change in any release.
 */
final class Jsonb {

    private static final String PACKAGE = "jakarta.json.bind.annotation.";


    private Jsonb() {
    }


    /** The name of a record component: {@code @McpParam(name)}, then {@code @JsonbProperty}, then the component name. */
    static String name(RecordComponent component) {
        McpParam info = component.getAnnotation(McpParam.class);
        if (info != null && !info.name().isEmpty()) {
            return info.name();
        }
        String renamed = propertyName(component.getAccessor(), recordField(component));
        return renamed != null ? renamed : component.getName();
    }

    /** The {@code @JsonbProperty} value on the first of these elements that has one, or null. */
    static String propertyName(AnnotatedElement... elements) {
        for (AnnotatedElement element : elements) {
            if (element != null && value(element, "JsonbProperty") instanceof String name && !name.isEmpty()) {
                return name;
            }
        }
        return null;
    }

    /** True if one of these elements has {@code @JsonbTransient}. */
    static boolean isTransient(AnnotatedElement... elements) {
        for (AnnotatedElement element : elements) {
            if (element != null && present(element, "JsonbTransient")) {
                return true;
            }
        }
        return false;
    }

    /** The names listed by {@code @JsonbPropertyOrder} on the class or a superclass; empty if there is none. */
    static List<String> order(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            if (value(c, "JsonbPropertyOrder") instanceof String[] names) {
                return List.of(names);
            }
        }
        return List.of();
    }

    /** The private field behind a bean property, searched up the class hierarchy; null if there is none. */
    static Field field(Class<?> type, String javaName) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(javaName);
            } catch (NoSuchFieldException e) {
                // the superclass may have it
            }
        }
        return null;
    }


    private static Field recordField(RecordComponent component) {
        try {
            return component.getDeclaringRecord().getDeclaredField(component.getName());
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    private static boolean present(AnnotatedElement element, String simpleName) {
        for (Annotation annotation : element.getAnnotations()) {
            if (annotation.annotationType().getName().equals(PACKAGE + simpleName)) {
                return true;
            }
        }
        return false;
    }

    /** The {@code value()} of the JSON-B annotation with this simple name, or null if it is absent. */
    private static Object value(AnnotatedElement element, String simpleName) {
        for (Annotation annotation : element.getAnnotations()) {
            if (annotation.annotationType().getName().equals(PACKAGE + simpleName)) {
                try {
                    return annotation.annotationType().getMethod("value").invoke(annotation);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    return null;
                }
            }
        }
        return null;
    }
}
