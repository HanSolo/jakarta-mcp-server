package eu.hansolo.mcp.api;

/**
 * The work behind a task: runs after the client has been given its task id, on an executor of the server. Return the final result
 * ({@link ToolResult.Complete}); throw {@link eu.hansolo.mcp.protocol.McpProtocolException} to end the task as {@code failed} with
 * that error. Any other exception ends it as {@code completed} with an {@code isError} result, like a failing tool call.
 */
@FunctionalInterface
public interface TaskWork {

    ToolResult run(TaskContext context) throws Exception;
}
