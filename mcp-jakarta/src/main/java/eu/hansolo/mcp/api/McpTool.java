package eu.hansolo.mcp.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Exposes a public method of a CDI bean as an MCP tool. Each parameter becomes a property of the tool's input schema (named
 * after the parameter, which needs {@code -parameters} at compile time, or {@link McpParam#name()}); the return value becomes
 * the result.
 *
 * <pre>{@code
 * @ApplicationScoped
 * public class Calculator {
 *
 *     @McpTool(description = "Adds two numbers", readOnlyHint = McpTool.Hint.TRUE)
 *     public double add(@McpParam(description = "First addend") double a, double b) {
 *         return a + b;
 *     }
 * }
 * }</pre>
 *
 * <p>Parameters: String, boolean, int, long, short, byte, double, float and their wrappers, BigInteger, BigDecimal, enums,
 * {@code List}/{@code Set}/{@code Collection}/arrays, {@code Map<String, V>}, records (nested objects), {@code Optional<T>}
 * for optional arguments, and {@code JsonObject}/{@code JsonArray}/{@code JsonValue} for anything free-form. A parameter of
 * type {@link ToolCall} or {@link Caller} is injected by the server and does not appear in the schema.
 *
 * <p>Return values: {@link ToolResult} (full control, including multi round-trip requests), {@code String} and other scalars
 * (text), a record or {@code JsonObject} or {@code Map} (structured content plus a text copy; records also get an
 * {@code outputSchema}), {@code void} (empty result). Throw {@link ToolExecutionException} to report a failure the model
 * should see; any other exception becomes a generic error.
 *
 * <p>The bean must be discoverable by CDI and the method public and non-static. One bean per class: the same class under two
 * qualifiers is registered once.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface McpTool {

    /** Tool name; defaults to the method name. 1-128 characters from {@code A-Z a-z 0-9 _ - .}. */
    String name() default "";

    /** Human-readable title; defaults to none (clients fall back to the name). */
    String title() default "";

    /** What the tool does, written for the model. */
    String description() default "";

    /** OAuth scopes the caller's token must carry (see {@code McpToolHandler#requiredScopes}). Requires {@code mcp.auth.enabled=true}. */
    String[] scopes() default {};

    /** Icon URLs ({@code https}, {@code http} or {@code data:}). Implement the handler interface for icons with a MIME type, sizes or a theme. */
    String[] icons() default {};

    /** The {@code _meta} object as JSON text, for example {@code "{\"com.example/team\":\"billing\"}"}. Empty means none. */
    String meta() default "";

    /** Spec annotation {@code readOnlyHint}: the tool does not modify its environment. Untrusted hint for clients. */
    Hint readOnlyHint() default Hint.UNSET;

    /** Spec annotation {@code destructiveHint}: may perform destructive updates (the spec default is true when not read-only). */
    Hint destructiveHint() default Hint.UNSET;

    /** Spec annotation {@code idempotentHint}: repeating a call with the same arguments has no additional effect. */
    Hint idempotentHint() default Hint.UNSET;

    /** Spec annotation {@code openWorldHint}: interacts with an open set of external entities (the web, say). */
    Hint openWorldHint() default Hint.UNSET;


    /** A tri-state flag, because "not stated" and "false" mean different things in the MCP tool annotations. */
    enum Hint {
        UNSET, TRUE, FALSE
    }
}
