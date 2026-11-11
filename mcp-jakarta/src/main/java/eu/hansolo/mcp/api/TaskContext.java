package eu.hansolo.mcp.api;

import jakarta.json.JsonObject;

/**
 * What the work of a task can see and do. The work runs on another thread than the request: request-scoped CDI beans are not
 * available in it, and the original request's progress channel is gone. Everything the work needs from the request is in {@link #call()}.
 */
public interface TaskContext {

    /** The id the client polls with; empty when the work runs inline for a client that did not declare the Tasks extension. */
    /** Which run of the work this is: 1 for the first, more when the task was restartable and another instance started it again. */
    default int attempt() {
        return 1;
    }

    String taskId();

    /** The original call: arguments, caller and trace context. Its progress reporter does nothing. */
    ToolCall call();

    /** Sets the status message shown by {@code tasks/get}, the only progress channel of a task. Each call writes to the task store, so do not call it in a tight loop. */
    void status(String message);

    /**
     * Asks the client for input and waits for the answers: the task becomes {@code input_required} until the client sends
     * {@code tasks/update}. The keys must not have been used before in this task.
     *
     * @return the answers, keyed like the requests
     * @throws RequestCancelledException if the task was cancelled or has expired while waiting
     * @throws eu.hansolo.mcp.protocol.McpProtocolException with error -32021 when the work runs inline, because input needs the Tasks extension
     */
    JsonObject ask(InputRequests requests);

    /** True once the client asked to cancel the task. Cooperative: stop soon and throw {@link RequestCancelledException} or return. */
    boolean isCancelled();
}
