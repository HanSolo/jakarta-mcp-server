package eu.hansolo.mcp.server;

import eu.hansolo.mcp.api.ToolResult;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Lets the other system tell a task that follows its job (see {@link ToolResult#externalTask}) that the job is done, without waiting for the next
 * poll. Inject it into the application's own callback endpoint:
 *
 * <pre>{@code
 * @Inject McpExternalJobs jobs;
 * ...
 * // POST /hooks/build/{taskId}, after the request has been authenticated
 * jobs.complete(taskId, ToolResult.text("Build " + number + " passed"));
 * }</pre>
 *
 * <p>The task id reaches the other system through {@link eu.hansolo.mcp.api.ExternalStart#start}. It is unguessable, but it is not a credential
 * for this endpoint: the application must authenticate the call itself (a signed token, the other system's own signature, mutual TLS), because
 * whoever may call these methods can end any task. A callback can be lost, so keep the {@link eu.hansolo.mcp.api.ExternalJob} poll as well.
 *
 * <p>Every method returns false if the task is unknown, does not follow a job, or has already ended, and true if it changed the task. They work on
 * every instance, whichever one started the job, as long as all instances share the task store.
 */
@Singleton
public class McpExternalJobs {

    private final TaskService tasks;


    @Inject
    public McpExternalJobs(TaskService tasks) {
        this.tasks = tasks;
    }


    /** Ends the task as {@code completed} with this result. A result larger than {@code mcp.tasks.max-result-bytes} fails the task. */
    public boolean complete(String taskId, ToolResult.Complete result) {
        return tasks.completeExternal(taskId, result);
    }

    /** Ends the task as {@code failed} with this message, which the client sees. */
    public boolean fail(String taskId, String message) {
        return tasks.failExternal(taskId, message);
    }

    /** Sets the status message that {@code tasks/get} shows. */
    public boolean progress(String taskId, String message) {
        return tasks.progressExternal(taskId, message);
    }
}
