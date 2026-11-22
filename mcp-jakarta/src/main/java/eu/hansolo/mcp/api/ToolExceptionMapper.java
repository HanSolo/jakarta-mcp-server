package eu.hansolo.mcp.api;

import java.util.Optional;

/**
 * Decides what the model is told when a tool fails with an exception. Without one, the model sees only {@code Tool execution
 * failed} (and the log has the stack trace), because the message of an arbitrary exception can reveal paths, SQL or credentials. A
 * {@link ToolExecutionException} always shows its own message and does not come here.
 *
 * <p>An application declares a CDI bean that implements this interface; the library finds every such bean. With several, the
 * annotation {@code jakarta.annotation.Priority} on the bean class orders them (the lowest value first; a class without it comes last),
 * and the first one that returns a message decides. If none does, the library's own rules apply: the message of a JAX-RS
 * {@code WebApplicationException} with a 4xx status and the message of a Bean Validation {@code ConstraintViolationException} are
 * shown, everything else is replaced by the generic text.
 *
 * <pre>
 * &#64;ApplicationScoped
 * public class BusinessFailures implements ToolExceptionMapper {
 *
 *     &#64;Override
 *     public Optional&lt;String&gt; message(Throwable failure, ToolCall call) {
 *         if (failure instanceof IllegalArgumentException || failure instanceof DuckNotFoundException) {
 *             return Optional.of(failure.getMessage());
 *         }
 *         return Optional.empty();
 *     }
 * }
 * </pre>
 *
 * <p>A message is cut at 2000 characters. A mapper that throws is logged and skipped. The mapper sees the exception as the tool threw
 * it, with the wrappers of reflection and containers removed ({@code InvocationTargetException}, {@code UndeclaredThrowableException},
 * {@code CompletionException}, {@code ExecutionException} and {@code EJBException}); it does not see failures of the protocol itself
 * (bad arguments, unknown tools, cancelled requests).
 *
 * <p>Implementations must be safe to call from several threads. The library calls them for tools of a request and for the work of tasks.
 */
@FunctionalInterface
public interface ToolExceptionMapper {

    /**
     * The text the model sees for this failure.
     *
     * @param failure the exception the tool threw, without wrappers
     * @param call    the call that failed: tool name, arguments and caller
     * @return the message for the model, or empty to leave the decision to the next mapper and finally to the library
     */
    Optional<String> message(Throwable failure, ToolCall call);
}
