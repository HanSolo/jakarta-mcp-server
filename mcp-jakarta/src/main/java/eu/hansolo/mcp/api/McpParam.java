package eu.hansolo.mcp.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Describes a parameter of an {@link McpTool} method, or a component of a record used as a parameter or result. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
public @interface McpParam {

    /** Property name; defaults to the Java name. Only needed when the code was compiled without {@code -parameters}. */
    String name() default "";

    /** Description for the model. */
    String description() default "";

    /**
     * Whether the argument must be present. Defaults to true for everything except {@code Optional}. Set false on an object
     * parameter to receive null when it is missing; primitives cannot be optional (use the wrapper type or {@code Optional}).
     */
    boolean required() default true;

    /**
     * Mirrors the argument into the request header {@code Mcp-Param-<header>} (the {@code x-mcp-header} schema annotation), so that
     * gateways can route on it. Only for string, integer and boolean parameters that are direct parameters of the tool method or of
     * nested records, never inside lists. Empty means no header.
     */
    String header() default "";
}
