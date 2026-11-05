package eu.hansolo.mcp.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Exposes a public method of a CDI bean as an MCP prompt.
 *
 * <pre>{@code
 * @McpPrompt(description = "Asks for a code review")
 * public String codeReview(@McpParam(description = "The code to review") String code, Optional<String> language) {
 *     return "Please review this " + language.orElse("") + " code:\n" + code;
 * }
 * }</pre>
 *
 * <p>Parameters become prompt arguments, which the protocol defines as strings: use {@code String} (required) or
 * {@code Optional<String>} / {@code @McpParam(required = false) String} (optional); {@link PromptCall} and {@link Caller} are
 * injected. Return types: {@code String} (one user message), {@link PromptMessage}, {@code List<PromptMessage>} or
 * {@link PromptResult}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface McpPrompt {

    /** Prompt name; defaults to the method name. */
    String name() default "";

    String title() default "";

    String description() default "";

    /** OAuth scopes the caller needs. */
    String[] scopes() default {};
}
