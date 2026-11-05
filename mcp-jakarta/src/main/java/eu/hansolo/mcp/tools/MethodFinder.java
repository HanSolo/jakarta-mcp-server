package eu.hansolo.mcp.tools;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/** Finds the methods of a class and its superclasses that carry a given annotation, and checks they can be called through a CDI proxy. */
final class MethodFinder {

    private MethodFinder() {
    }


    static boolean hasAnnotated(Class<?> beanClass, Class<? extends Annotation> annotation) {
        try {
            for (Class<?> c = beanClass; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method method : c.getDeclaredMethods()) {
                    if (method.isAnnotationPresent(annotation)) {
                        return true;
                    }
                }
            }
        } catch (LinkageError e) {
            // a bean whose dependencies are not loadable cannot be a provider we could call anyway
        }
        return false;
    }

    /** @throws IllegalStateException for annotated methods that are not public instance methods */
    static List<Method> annotated(Class<?> beanClass, Class<? extends Annotation> annotation) {
        List<Method> found = new ArrayList<>();
        for (Class<?> c = beanClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                if (method.isBridge() || method.isSynthetic() || !method.isAnnotationPresent(annotation)) {
                    continue;
                }
                int modifiers = method.getModifiers();
                if (!Modifier.isPublic(modifiers) || Modifier.isStatic(modifiers)) {
                    throw new IllegalStateException(c.getName() + "." + method.getName() + ": @" + annotation.getSimpleName() + " methods must be public and non-static (the call goes through the CDI proxy)");
                }
                found.add(method);
            }
        }
        return found;
    }
}
