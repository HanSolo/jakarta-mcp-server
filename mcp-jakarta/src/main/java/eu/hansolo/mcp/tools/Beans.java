package eu.hansolo.mcp.tools;

import eu.hansolo.mcp.api.McpParam;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plain JavaBeans as results: the properties are the public getters ({@code getX()}, and {@code isX()} for a {@code boolean}) and the
 * public instance fields, sorted by name. A property with {@code null} or an empty {@code Optional} is left out. A bean is read, never
 * built: parameters of a tool are still records.
 *
 * <p>Limits: beans nest at most {@link #MAX_DEPTH} levels (a record, list or map between two beans does not count), a bean that
 * contains itself is refused with the path of the cycle, and proxies (a lazy-loading JPA entity, a CDI or EJB proxy, a
 * {@code java.lang.reflect.Proxy}) are refused, because reading them would load the database or fail. JDK and Jakarta classes are no
 * beans.
 *
 * <p><b>Internal.</b> Not part of the stable API: used by {@link SchemaGenerator} and {@link JsonMapper}, and it can change in any
 * release.
 */
final class Beans {

    /** How many beans can contain one another on the way from the result to a value. */
    static final int MAX_DEPTH = 8;

    private static final String[]                  PROXY_MARKS = {"$HibernateProxy", "$$_jvst", "_$$_", "$$EnhancerBy", "$ByteBuddy$", "WeldClientProxy", "$Proxy$", "$$Lambda"};
    private static final String[]                  JDK_PACKAGES = {"java.", "javax.", "jdk.", "sun.", "com.sun.", "jakarta."};
    private static final Map<Class<?>, List<Property>> CACHE = new ConcurrentHashMap<>();


    /** One readable property. */
    record Property(String name, Type type, Method getter, Field field, McpParam info) {

        Object read(Object bean) throws ReflectiveOperationException {
            return getter != null ? getter.invoke(bean) : field.get(bean);
        }
    }


    private Beans() {
    }


    /** True if values of this class are written as an object made of their getters. */
    static boolean isBean(Class<?> type) {
        if (type.isPrimitive() || type.isArray() || type.isInterface() || type.isEnum() || type.isRecord() || type.isAnnotation() || type.isAnonymousClass()
            || Modifier.isAbstract(type.getModifiers())) {
            return false;
        }
        for (String prefix : JDK_PACKAGES) {
            if (type.getName().startsWith(prefix)) {
                return false;
            }
        }
        return !(CharSequence.class.isAssignableFrom(type) || Number.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type) || Collection.class.isAssignableFrom(type)
            || Optional.class.isAssignableFrom(type) || Throwable.class.isAssignableFrom(type) || ValueTypes.isValueClass(type));
    }

    /** The reason why this class cannot be read, or null. */
    static String refusal(Class<?> type) {
        if (Proxy.isProxyClass(type)) {
            return type.getName() + " is a java.lang.reflect.Proxy";
        }
        for (String mark : PROXY_MARKS) {
            if (type.getName().contains(mark)) {
                return type.getName() + " is a proxy (a lazy-loading entity, or a CDI or EJB proxy)";
            }
        }
        for (Class<?> i : type.getInterfaces()) {
            if (i.getName().equals("org.hibernate.proxy.HibernateProxy") || i.getName().equals("javassist.util.proxy.ProxyObject")) {
                return type.getName() + " is a proxy (a lazy-loading entity)";
            }
        }
        return null;
    }

    /** The properties of the class, sorted by name. */
    static List<Property> properties(Class<?> type) {
        return CACHE.computeIfAbsent(type, Beans::find);
    }


    private static List<Property> find(Class<?> type) {
        Map<String, Property> found = new LinkedHashMap<>();
        for (Method method : type.getMethods()) {
            String name = propertyName(method);
            if (name != null && !found.containsKey(name)) {
                try {
                    method.setAccessible(true);
                } catch (RuntimeException e) {
                    // a public method of a public class works without it
                }
                found.put(name, new Property(name, method.getGenericReturnType(), method, null, method.getAnnotation(McpParam.class)));
            }
        }
        for (Field field : type.getFields()) {
            int modifiers = field.getModifiers();
            if (!Modifier.isStatic(modifiers) && !Modifier.isTransient(modifiers) && !found.containsKey(field.getName())) {
                found.put(field.getName(), new Property(field.getName(), field.getGenericType(), null, field, field.getAnnotation(McpParam.class)));
            }
        }
        List<Property> sorted = new ArrayList<>(found.values());
        sorted.sort(Comparator.comparing(Property::name));
        return List.copyOf(sorted);
    }

    /** The property that a public getter stands for, or null if the method is no getter. */
    private static String propertyName(Method method) {
        if (method.getParameterCount() != 0 || Modifier.isStatic(method.getModifiers()) || method.isBridge() || method.isSynthetic() || method.getDeclaringClass() == Object.class
            || method.getReturnType() == void.class) {
            return null;
        }
        String name = method.getName();
        if (name.startsWith("get") && name.length() > 3) {
            return decapitalize(name.substring(3));
        }
        if (name.startsWith("is") && name.length() > 2 && (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class)) {
            return decapitalize(name.substring(2));
        }
        return null;
    }

    /** As {@code java.beans.Introspector}: {@code Name} becomes {@code name}, and {@code URL} stays. */
    static String decapitalize(String name) {
        if (name.length() > 1 && Character.isUpperCase(name.charAt(0)) && Character.isUpperCase(name.charAt(1))) {
            return name;
        }
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }
}
