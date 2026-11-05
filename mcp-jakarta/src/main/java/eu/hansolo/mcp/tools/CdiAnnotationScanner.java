package eu.hansolo.mcp.tools;

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import eu.hansolo.mcp.api.McpCompletionHandler;
import eu.hansolo.mcp.api.McpPromptHandler;
import eu.hansolo.mcp.api.McpResourceHandler;
import eu.hansolo.mcp.api.McpResourceTemplateHandler;
import eu.hansolo.mcp.api.McpToolHandler;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds the beans that carry {@code @McpTool}, {@code @McpResource} or {@code @McpPrompt} methods and wraps each method in a
 * handler. Every call fetches a fresh contextual reference, so scopes, interceptors and decorators of the bean apply exactly as for
 * any other CDI call. Dependent-scoped beans are created per call and destroyed after it.
 */
@Singleton
public class CdiAnnotationScanner {

    private final List<McpToolHandler>             tools     = new ArrayList<>();
    private final List<McpResourceHandler>         resources = new ArrayList<>();
    private final List<McpResourceTemplateHandler> templates = new ArrayList<>();
    private final List<McpPromptHandler>           prompts   = new ArrayList<>();
    private final List<McpCompletionHandler>       completions = new ArrayList<>();


    @Inject
    public CdiAnnotationScanner(BeanManager beanManager) {
        Instance<Object> root = beanManager.createInstance();
        Set<Class<?>> seen = new HashSet<>();
        for (Bean<?> bean : beanManager.getBeans(Object.class, Any.Literal.INSTANCE)) {
            Class<?> beanClass = bean.getBeanClass();
            // producer methods and fields report their declaring class as bean class; their types do not include it
            if (!bean.getTypes().contains(beanClass) || !seen.add(beanClass)) {
                continue;
            }
            boolean hasTools = ToolScanner.hasTools(beanClass);
            boolean hasResources = ResourceScanner.hasResources(beanClass);
            boolean hasPrompts = PromptScanner.hasPrompts(beanClass);
            boolean hasCompletions = CompletionScanner.hasCompletions(beanClass);
            if (!hasTools && !hasResources && !hasPrompts && !hasCompletions) {
                continue;
            }
            BeanSource source = sourceFor(root, bean, beanClass);
            if (hasTools) {
                tools.addAll(ToolScanner.scan(beanClass, source));
            }
            if (hasResources) {
                ResourceScanner.Found found = ResourceScanner.scan(beanClass, source);
                resources.addAll(found.resources());
                templates.addAll(found.templates());
            }
            if (hasPrompts) {
                prompts.addAll(PromptScanner.scan(beanClass, source));
            }
            if (hasCompletions) {
                completions.addAll(CompletionScanner.scan(beanClass, source));
            }
        }
    }


    public List<McpToolHandler> tools() {
        return List.copyOf(tools);
    }

    public List<McpResourceHandler> resources() {
        return List.copyOf(resources);
    }

    public List<McpResourceTemplateHandler> templates() {
        return List.copyOf(templates);
    }

    public List<McpPromptHandler> prompts() {
        return List.copyOf(prompts);
    }

    public List<McpCompletionHandler> completions() {
        return List.copyOf(completions);
    }

    private static <T> BeanSource sourceFor(Instance<Object> root, Bean<?> bean, Class<T> beanClass) {
        Instance<T> instance = root.select(beanClass, bean.getQualifiers().toArray(new Annotation[0]));
        boolean dependent = bean.getScope() == Dependent.class;
        return () -> {
            Instance.Handle<T> handle = instance.getHandle();
            return new BeanSource.Lease() {
                @Override
                public Object instance() {
                    return handle.get();
                }

                @Override
                public void close() {
                    if (dependent) {
                        handle.destroy();
                    }
                }
            };
        };
    }
}
