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


    /** True if the class declares prompt methods. */
    public static boolean hasPrompts(Class<?> beanClass) {
        return MethodFinder.hasAnnotated(beanClass, McpPrompt.class);
    }

    /** The prompt handlers of the annotated methods of the class; each call uses the bean the source supplies. */
    public static List<McpPromptHandler> scan(Class<?> beanClass, BeanSource source) {
        List<McpPromptHandler> handlers = new ArrayList<>();
        for (Method method : MethodFinder.annotated(beanClass, McpPrompt.class)) {
            handlers.add(new AnnotatedPromptHandler(method, source));
        }
        return handlers;
    }
}
