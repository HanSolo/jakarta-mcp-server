package eu.hansolo.mcp.api;

import jakarta.json.JsonObject;

/**
 * Follows the jobs that one tool starts in another system (a batch run, a CI build, a training job). Implement it as a CDI bean for each such tool
 * and return {@link ToolResult#externalTask(JsonObject)} from the tool; the server then keeps no thread for the job. It stores the job's handle
 * with the task, asks {@link #poll} about it every {@code mcp.tasks.external-poll-ms} on any of the instances, and ends the task when the poll
 * says so. A callback from the other system can end it sooner, through {@link eu.hansolo.mcp.server.McpExternalJobs}.
 *
 * <p>The methods run on a thread of the task executor, not on a request, so request-scoped beans are not available. Because the poll may run on
 * any instance, and twice at about the same time when an instance is slow, {@code poll} must only read.
 */
public interface ExternalJob {

    /** The name of the tool whose jobs this follows. Exactly one {@code ExternalJob} may name a tool. */
    String tool();

    /**
     * Asks the other system about the job.
     *
     * @param taskId the task the job belongs to
     * @param handle what {@link ExternalStart#start} returned
     * @throws Exception if the other system cannot be reached right now; the server asks again, and ends the task as failed after
     *                   {@code mcp.tasks.external-max-failures} failures in a row
     */
    ExternalJobStatus poll(String taskId, JsonObject handle) throws Exception;

    /**
     * Stops the job after the client sent {@code tasks/cancel}. The task becomes {@code cancelled} when this returns; if it throws, the server
     * calls it again with the next poll, until it works or the failure limit is reached. It may be called more than once for a job.
     */
    default void cancel(String taskId, JsonObject handle) throws Exception {
    }
}
