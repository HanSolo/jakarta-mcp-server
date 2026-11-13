package eu.hansolo.mcp.tools;

import eu.hansolo.mcp.api.McpPrompt;
import eu.hansolo.mcp.api.McpPromptHandler;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Finds {@link McpPrompt} methods. */
public final class PromptScanner {

    private PromptScanner() {
    }


    public static boolean hasPrompts(Class<?> beanClass) {
        return MethodFinder.hasAnnotated(beanClass, McpPrompt.class);
    }

    public static List<McpPromptHandler> scan(Class<?> beanClass, BeanSource source) {
        List<McpPromptHandler> handlers = new ArrayList<>();
        for (Method method : MethodFinder.annotated(beanClass, McpPrompt.class)) {
            handlers.add(new AnnotatedPromptHandler(method, source));
        }
        return handlers;
    }
}
