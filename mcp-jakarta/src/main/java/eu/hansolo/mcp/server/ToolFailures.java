package eu.hansolo.mcp.server;

import jakarta.annotation.Priority;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import eu.hansolo.mcp.api.ToolCall;
import eu.hansolo.mcp.api.ToolExceptionMapper;
import eu.hansolo.mcp.api.ToolExecutionException;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * What the model is told when a tool fails with an exception: the message of a {@link ToolExecutionException}, the answer of the first
 * {@link ToolExceptionMapper} that has one, the message of a few exceptions that are meant for callers (a JAX-RS
 * {@code WebApplicationException} with a 4xx status, a Bean Validation {@code ConstraintViolationException}), or the generic text.
 * Used by the request handler and by the task service.
 *
 * <p><b>Internal.</b> Not part of the stable API: this class is public only because other packages of this library use it, and it can
 * change in any release. Applications implement {@link ToolExceptionMapper} instead.
 */
@Singleton
public class ToolFailures {

    private static final Logger LOG = Logger.getLogger(ToolFailures.class.getName());

    /** The text for a failure that nobody wants to explain. */
    public static final String GENERIC = "Tool execution failed";

    /** The longest message the model gets; a longer one is cut. */
    static final int MAX_MESSAGE = 2000;

    private final List<ToolExceptionMapper> mappers;


    @Inject
    public ToolFailures(Instance<ToolExceptionMapper> available) {
        this(available == null ? List.<ToolExceptionMapper>of() : sorted(available));
    }

    /** With these mappers, in this order (for code that is built without CDI). */
    public ToolFailures(List<? extends ToolExceptionMapper> mappers) {
        this.mappers = List.copyOf(mappers);
    }


    /** No mappers: only the library's own rules apply. */
    public static ToolFailures none() {
        return new ToolFailures(List.<ToolExceptionMapper>of());
    }


    /**
     * The message for the model.
     *
     * @param failure the exception the tool threw
     * @param call    the call that failed
     */
    public String message(Throwable failure, ToolCall call) {
        Throwable cause = unwrap(failure);
        if (cause instanceof ToolExecutionException shown) {
            return limit(shown.getMessage() == null ? GENERIC : shown.getMessage());
        }
        for (ToolExceptionMapper mapper : mappers) {
            try {
                Optional<String> text = mapper.message(cause, call);
                if (text != null && text.isPresent() && !text.get().isBlank()) {
                    LOG.log(Level.FINE, "Tool '" + call.name() + "' failed with " + cause.getClass().getName() + "; " + mapper.getClass().getName() + " chose the message");
                    return limit(text.get());
                }
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "The exception mapper " + mapper.getClass().getName() + " failed", e);
            }
        }
        String known = ownRules(cause);
        if (known != null && !known.isBlank()) {
            LOG.log(Level.FINE, "Tool '" + call.name() + "' failed with " + cause.getClass().getName() + ": " + known);
            return limit(known);
        }
        LOG.log(Level.WARNING, "Tool '" + call.name() + "' failed", failure);
        return GENERIC;
    }


    /** The mapper beans, ordered by the {@code @Priority} of their class (lowest first, none last, then by class name). */
    private static List<ToolExceptionMapper> sorted(Instance<ToolExceptionMapper> available) {
        record Ranked(int priority, String name, ToolExceptionMapper mapper) {
        }
        List<Ranked> ranked = new ArrayList<>();
        for (Instance.Handle<ToolExceptionMapper> handle : available.handles()) {
            Class<?> beanClass = handle.getBean().getBeanClass();
            Priority priority  = beanClass.getAnnotation(Priority.class);
            ranked.add(new Ranked(priority == null ? Integer.MAX_VALUE : priority.value(), beanClass.getName(), handle.get()));
        }
        ranked.sort(Comparator.comparingInt(Ranked::priority).thenComparing(Ranked::name));
        return ranked.stream().map(Ranked::mapper).toList();
    }

    /** The exception under the wrappers of reflection, executors and EJB. */
    static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        for (int i = 0; i < 10 && current.getCause() != null && isWrapper(current); i++) {
            current = current.getCause();
        }
        return current;
    }

    private static boolean isWrapper(Throwable t) {
        return t instanceof InvocationTargetException || t instanceof UndeclaredThrowableException || t instanceof CompletionException || t instanceof ExecutionException
            || hasSuperclass(t.getClass(), "jakarta.ejb.EJBException");
    }

    /** True if the class or one of its superclasses has this name (by name, so that the API need not be on the class path). */
    private static boolean hasSuperclass(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            if (name.equals(c.getName())) {
                return true;
            }
        }
        return false;
    }

    /** The message of the exceptions that are written for the caller, or null. */
    private static String ownRules(Throwable cause) {
        if (hasSuperclass(cause.getClass(), "jakarta.validation.ConstraintViolationException")) {
            return cause.getMessage();
        }
        try {
            return JaxRs.message(cause);
        } catch (LinkageError e) {
            return null;   // no JAX-RS here
        }
    }

    private static String limit(String text) {
        return text.length() <= MAX_MESSAGE ? text : text.substring(0, MAX_MESSAGE - 1) + "…";
    }


    /** Kept apart, so that the class loads only where JAX-RS is present. */
    private static final class JaxRs {

        static String message(Throwable cause) {
            if (cause instanceof jakarta.ws.rs.WebApplicationException web) {
                int status = web.getResponse().getStatus();
                return status >= 400 && status < 500 ? web.getMessage() : null;
            }
            return null;
        }
    }
}
