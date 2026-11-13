package eu.hansolo.mcp.tools;

import eu.hansolo.mcp.api.McpComplete;
import eu.hansolo.mcp.api.McpCompletionHandler;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Finds {@link McpComplete} methods. */
public final class CompletionScanner {

    private CompletionScanner() {
    }


    public static boolean hasCompletions(Class<?> beanClass) {
        return MethodFinder.hasAnnotated(beanClass, McpComplete.class);
    }

    public static List<McpCompletionHandler> scan(Class<?> beanClass, BeanSource source) {
        List<McpCompletionHandler> handlers = new ArrayList<>();
        for (Method method : MethodFinder.annotated(beanClass, McpComplete.class)) {
            handlers.add(new AnnotatedCompletionHandler(method, source));
        }
        return handlers;
    }
}
