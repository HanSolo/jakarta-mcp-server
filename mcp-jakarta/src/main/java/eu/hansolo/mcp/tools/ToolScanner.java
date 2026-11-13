package eu.hansolo.mcp.tools;

import eu.hansolo.mcp.api.McpTool;
import eu.hansolo.mcp.api.McpToolHandler;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds {@link McpTool} methods by reflection. Independent of CDI, so a test can build the handlers of a tool bean by hand:
 * {@code ToolScanner.scan(MyTools.class, BeanSource.of(new MyTools(...)))}. This and the other scanners' {@code hasX} and {@code scan}
 * methods are part of the stable API; the handler classes behind the returned interfaces are not.
 */
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
    public static List<McpToolHandler> scan(Class<?> beanClass, BeanSource source) {
        List<McpToolHandler> handlers = new ArrayList<>();
        for (Method method : MethodFinder.annotated(beanClass, McpTool.class)) {
            handlers.add(new AnnotatedToolHandler(method, source));
        }
        return handlers;
    }
}
