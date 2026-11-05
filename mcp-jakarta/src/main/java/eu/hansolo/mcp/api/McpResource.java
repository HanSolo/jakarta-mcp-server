package eu.hansolo.mcp.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Exposes a public method of a CDI bean as an MCP resource. Set exactly one of {@link #uri()} (a fixed resource) and
 * {@link #uriTemplate()} (a family of resources).
 *
 * <pre>{@code
 * @McpResource(uri = "config://app", name = "app-config", mimeType = "application/json")
 * public AppConfig config() { ... }
 *
 * @McpResource(uriTemplate = "greeting://{name}", name = "greeting")
 * public String greeting(String name) { return "Hello, " + name + "!"; }
 * }</pre>
 *
 * <p>Parameters: for a template, {@code String} parameters named after template variables (values arrive percent-decoded and are
 * client input, so check them); {@link ResourceCall} and {@link Caller} are injected. Return types: {@code String} (text),
 * {@code byte[]} (binary), {@link ResourceContent}, {@code List<ResourceContent>}, {@link ResourceResult}, or a record, {@code Map},
 * {@code List} or JSON-P value (serialised as JSON text, default MIME type {@code application/json}).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface McpResource {

    /** The fixed URI, for example {@code config://app}. */
    String uri() default "";

    /** An RFC 6570 template using {@code {name}} and {@code {+name}}. */
    String uriTemplate() default "";

    /** Programmatic name; defaults to the method name. */
    String name() default "";

    String title() default "";

    String description() default "";

    /** MIME type advertised and used for returned content; defaults depend on the return type. */
    String mimeType() default "";

    /** OAuth scopes the caller needs. */
    String[] scopes() default {};
}
