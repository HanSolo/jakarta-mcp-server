package eu.hansolo.mcp.tools;

import eu.hansolo.mcp.api.McpResource;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Finds {@link McpResource} methods and wraps them as fixed-resource or template handlers. */
public final class ResourceScanner {

    private ResourceScanner() {
    }


    public static boolean hasResources(Class<?> beanClass) {
        return MethodFinder.hasAnnotated(beanClass, McpResource.class);
    }

    /** The result of scanning one class: fixed resources and templates, in method order. */
    public record Found(List<AnnotatedResourceHandler> resources, List<AnnotatedTemplateHandler> templates) {
    }

    /**
     * @throws IllegalStateException when a method sets both or neither of {@code uri} and {@code uriTemplate}, is not public, or cannot be bound
     */
    public static Found scan(Class<?> beanClass, BeanSource source) {
        List<AnnotatedResourceHandler> resources = new ArrayList<>();
        List<AnnotatedTemplateHandler> templates = new ArrayList<>();
        for (Method method : MethodFinder.annotated(beanClass, McpResource.class)) {
            McpResource annotation = method.getAnnotation(McpResource.class);
            boolean fixed = !annotation.uri().isEmpty();
            boolean templated = !annotation.uriTemplate().isEmpty();
            if (fixed == templated) {
                throw new IllegalStateException(beanClass.getName() + "." + method.getName() + ": @McpResource needs exactly one of uri and uriTemplate");
            }
            if (fixed) {
                resources.add(new AnnotatedResourceHandler(method, source));
            } else {
                templates.add(new AnnotatedTemplateHandler(method, source));
            }
        }
        return new Found(resources, templates);
    }
}
