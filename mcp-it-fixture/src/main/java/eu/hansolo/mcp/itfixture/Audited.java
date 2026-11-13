package eu.hansolo.mcp.itfixture;

import jakarta.interceptor.InterceptorBinding;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/** Marks a tool method that {@link AuditInterceptor} wraps: an ordinary CDI interceptor binding, nothing the MCP library knows about. */
@InterceptorBinding
@Retention(RUNTIME)
@Target({TYPE, METHOD})
public @interface Audited {
}
