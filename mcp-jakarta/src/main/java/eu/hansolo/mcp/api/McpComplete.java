package eu.hansolo.mcp.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Exposes a public method of a CDI bean as the completion source for one prompt argument or one resource template variable. Set
 * exactly one of {@link #prompt()} and {@link #resourceTemplate()}.
 *
 * <pre>{@code
 * @McpComplete(prompt = "summarize", argument = "style")
 * public List<String> styles(String typed) {
 *     return List.of("bullet points", "one sentence").stream().filter(s -> s.startsWith(typed)).toList();
 * }
 * }</pre>
 *
 * <p>Parameters: at most one {@code String} (what the user has typed so far), plus optionally {@link CompletionCall} (the other
 * argument values, client capabilities) and {@link Caller}. Return {@code List<String>} or {@link CompletionResult}; the server
 * cuts the list at 100 values.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface McpComplete {

    /** The prompt whose argument this completes. */
    String prompt() default "";

    /** The resource template (exactly as in {@link McpResource#uriTemplate()}) whose variable this completes. */
    String resourceTemplate() default "";

    /** The prompt argument or template variable. */
    String argument();

    /** Extra OAuth scopes for the suggestions. */
    String[] scopes() default {};
}
