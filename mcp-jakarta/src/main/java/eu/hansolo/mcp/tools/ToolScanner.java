package eu.hansolo.mcp.tools;

import eu.hansolo.mcp.api.McpTool;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Finds {@link McpTool} methods by reflection. Independent of CDI so it can be tested and reused with other containers. */
public final class ToolScanner {

    private ToolScanner() {
    }


    /** Cheap check used to skip the thousands of beans that have nothing to do with MCP. */
    public static boolean hasTools(Class<?> beanClass) {
        return MethodFinder.hasAnnotated(beanClass, McpTool.class);
    }

    /**
     * One handler per annotated method of the class and its superclasses.
     *
     * @throws IllegalStateException for annotated methods that are not public instance methods, or whose signature cannot be described
     */
    public static List<AnnotatedToolHandler> scan(Class<?> beanClass, BeanSource source) {
        List<AnnotatedToolHandler> handlers = new ArrayList<>();
        for (Method method : MethodFinder.annotated(beanClass, McpTool.class)) {
            handlers.add(new AnnotatedToolHandler(method, source));
        }
        return handlers;
    }
}
