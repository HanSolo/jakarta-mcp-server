package eu.hansolo.mcp.itfixture;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import eu.hansolo.mcp.api.ToolCall;
import eu.hansolo.mcp.api.ToolExecutionException;
import eu.hansolo.mcp.api.ToolResult;

/**
 * What an application can do around a tool with a plain CDI interceptor, without any support from the MCP library: record every
 * call (audit), refuse a call before the tool runs (policy gate) and cap a burst of calls (rate limit). The demo rules are
 * deliberately silly: the text {@code blocked} is refused, and texts that start with {@code burst} are allowed three times.
 *
 * <p>A refusal is a {@link ToolResult#error} for a method that returns a {@code ToolResult} ({@code McpToolHandler.call}) and a
 * {@link ToolExecutionException} for any other tool method: both reach the client as a result with {@code isError}.
 */
@Audited
@Interceptor
@Priority(Interceptor.Priority.APPLICATION)
public class AuditInterceptor {

    @Inject CallLog log;


    @AroundInvoke
    public Object around(InvocationContext context) throws Exception {
        String tool = context.getMethod().getName();
        String text = textOf(context.getParameters());
        log.record("call " + tool + " " + text);
        if ("blocked".equals(text)) {
            return refuse(context, tool, "denied by policy");
        }
        if (text.startsWith("burst") && log.count("burst") > 3) {
            return refuse(context, tool, "rate limit exceeded");
        }
        return context.proceed();
    }

    private Object refuse(InvocationContext context, String tool, String reason) {
        log.record("refused " + tool + " " + reason);
        if (context.getMethod().getReturnType() == ToolResult.class) {
            return ToolResult.error(reason);
        }
        throw new ToolExecutionException(reason);
    }

    /** The {@code message} argument, whether the method takes it as a parameter or reads it from the {@link ToolCall}. */
    private static String textOf(Object[] parameters) {
        for (Object parameter : parameters) {
            if (parameter instanceof String text) {
                return text;
            }
            if (parameter instanceof ToolCall call) {
                return call.arguments().getString("message", "");
            }
        }
        return "";
    }
}
