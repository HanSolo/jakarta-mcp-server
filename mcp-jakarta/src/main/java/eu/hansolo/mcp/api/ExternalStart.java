package eu.hansolo.mcp.api;

import jakarta.json.JsonObject;

/**
 * Starts a job in another system and returns its handle. The server calls it once, after it has chosen the task id and before it stores the
 * task, so the id can be given to the other system as a correlation key for a callback.
 */
@FunctionalInterface
public interface ExternalStart {

    /**
     * @param taskId the id of the task the job belongs to
     * @return what {@link ExternalJob#poll} needs to ask about the job later (a job id, a URL, ...); a JSON object of at most 16 KiB.
     *         It is stored with the task but never sent to the client, and it is dropped once the task ends. Keep secrets out of it where you can.
     * @throws eu.hansolo.mcp.protocol.McpProtocolException to refuse the call with that error; any other exception becomes an internal error
     */
    JsonObject start(String taskId) throws Exception;
}
