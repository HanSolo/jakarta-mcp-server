package eu.hansolo.mcp.api;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;

/**
 * The outcome of a tool call: either a final result or a request for more input from the client.
 *
 * <p><b>Sealed.</b> Applications create these values with the factory methods and return them; they do not switch over them. A minor release
 * can add a variant (the 0.9.0 release added one to {@code ToolResult}), which would break an exhaustive {@code switch} in application code.
 */
public sealed interface ToolResult permits ToolResult.Complete, ToolResult.InputRequired, ToolResult.Task, ToolResult.ExternalTask {

    /**
     * A final result.
     *
     * @param content           unstructured content blocks, never null
     * @param structuredContent optional structured result (any JSON value), or null
     * @param isError           true if the tool ran but failed; the model gets to see the message and can recover
     * @param meta              optional {@code _meta} of the result (not shown to the model by most clients; MCP Apps and
     *                          extensions use it), or null
     */
    record Complete(JsonArray content, JsonValue structuredContent, boolean isError, JsonObject meta) implements ToolResult {

        public Complete {
            Meta.validate(meta, "tool result _meta");
        }

        public Complete(JsonArray content, JsonValue structuredContent, boolean isError) {
            this(content, structuredContent, isError, null);
        }

        /** The same result with a {@code _meta} object. The server's own {@code serverInfo} key is added on top. */
        public Complete withMeta(JsonObject meta) {
            return new Complete(content, structuredContent, isError, meta);
        }
    }

    /**
     * Multi Round-Trip Request: the tool needs something from the client (for example an elicitation) before it can finish.
     * The client retries the original call with {@code inputResponses} attached. At least one field must be non-null.
     */
    record InputRequired(JsonObject inputRequests, String requestState) implements ToolResult {

        public InputRequired {
            if (inputRequests == null && requestState == null) {
                throw new IllegalArgumentException("inputRequests or requestState is required");
            }
        }
    }

    /**
     * Run the work as a task (Tasks extension): the client gets a task id at once and polls for the result. A client that did not
     * declare the extension gets the plain result instead, because the server runs the work on the request thread. Needs
     * {@code mcp.tasks.enabled}; without it the work always runs inline.
     *
     * @param work       what to run; see {@link TaskWork}
     * @param ttlMs      how long the task is kept, counted from its creation, or null for the server default
     * @param noExpiry   true to keep the task without limit (then {@code ttlMs} must be null)
     * @param pollIntervalMs suggested polling interval, or null for the server default
     * @param restartable true if another instance may run the call again from the start when the instance running it is lost; see {@link #withRestartable()}
     */
    record Task(TaskWork work, Long ttlMs, boolean noExpiry, Long pollIntervalMs, boolean restartable) implements ToolResult {

        public Task {
            if (work == null) {
                throw new IllegalArgumentException("A task needs work to run");
            }
            if (ttlMs != null && (noExpiry || ttlMs <= 0)) {
                throw new IllegalArgumentException("ttlMs must be positive and cannot be combined with noExpiry");
            }
            if (pollIntervalMs != null && pollIntervalMs <= 0) {
                throw new IllegalArgumentException("pollIntervalMs must be positive");
            }
        }

        /** Not restartable. */
        public Task(TaskWork work, Long ttlMs, boolean noExpiry, Long pollIntervalMs) {
            this(work, ttlMs, noExpiry, pollIntervalMs, false);
        }

        public Task withTtlMs(long ttlMs) {
            return new Task(work, ttlMs, false, pollIntervalMs, restartable);
        }

        /** Keep the task until the server's store drops it for other reasons. */
        public Task withoutExpiry() {
            return new Task(work, null, true, pollIntervalMs, restartable);
        }

        public Task withPollIntervalMs(long pollIntervalMs) {
            return new Task(work, ttlMs, noExpiry, pollIntervalMs, restartable);
        }

        /**
         * Lets the server run this tool call again from the start, on any instance, if the instance running it stops before it finishes
         * (at most {@code mcp.tasks.max-attempts} runs in all; then the task fails as usual).
         *
         * <p>This is at-least-once: the first run may have done part of the work, and after a long pause it may even still be running
         * for a moment, so the work must be safe to repeat. To make a restart possible the server stores the call (arguments, caller and
         * client capabilities) with the task, and runs it with the caller's scopes without checking the access token again.
         * Answers to earlier input requests are dropped; the work asks again.
         */
        public Task withRestartable() {
            return new Task(work, ttlMs, noExpiry, pollIntervalMs, true);
        }
    }

    /**
     * The tool started a job in another system and the task follows it without holding a thread: the server stores the handle, asks the
     * {@link ExternalJob} of this tool about the job every {@code mcp.tasks.external-poll-ms} (on any instance), and ends the task when the
     * job is done. Needs {@code mcp.tasks.enabled} and a client that declared the Tasks extension; any other client is refused with
     * -32021, before {@code start} is called, so no job is started that nobody follows. Check {@link ToolCall#clientSupportsTasks()} first if
     * the tool should do something else for such clients.
     *
     * @param start            starts the job once the task id is known; see {@link ExternalStart}
     * @param ttlMs            how long the task is kept, counted from its creation, or null for the server default
     * @param noExpiry         true to keep the task without limit (then {@code ttlMs} must be null)
     * @param pollIntervalMs   suggested polling interval for the client, or null for the server default
     */
    record ExternalTask(ExternalStart start, Long ttlMs, boolean noExpiry, Long pollIntervalMs) implements ToolResult {

        public ExternalTask {
            if (start == null) {
                throw new IllegalArgumentException("An external task needs a start");
            }
            if (ttlMs != null && (noExpiry || ttlMs <= 0)) {
                throw new IllegalArgumentException("ttlMs must be positive and cannot be combined with noExpiry");
            }
            if (pollIntervalMs != null && pollIntervalMs <= 0) {
                throw new IllegalArgumentException("pollIntervalMs must be positive");
            }
        }

        public ExternalTask withTtlMs(long ttlMs) {
            return new ExternalTask(start, ttlMs, false, pollIntervalMs);
        }

        /** Keep the task until the server's store drops it for other reasons. */
        public ExternalTask withoutExpiry() {
            return new ExternalTask(start, null, true, pollIntervalMs);
        }

        public ExternalTask withPollIntervalMs(long pollIntervalMs) {
            return new ExternalTask(start, ttlMs, noExpiry, pollIntervalMs);
        }
    }

    static Complete text(String text) {
        return new Complete(Json.createArrayBuilder().add(textBlock(text)).build(), null, false);
    }

    /** A tool failure the model should see. Do not put secrets or stack traces in the message. */
    static Complete error(String message) {
        return new Complete(Json.createArrayBuilder().add(textBlock(message)).build(), null, true);
    }

    /** Structured result; the JSON text is also returned as a text block for clients that ignore structuredContent. */
    static Complete structured(JsonObject data) {
        return new Complete(Json.createArrayBuilder().add(textBlock(data.toString())).build(), data, false);
    }

    /** A result made of content blocks built with {@link Content}. */
    static Complete of(JsonObject... blocks) {
        return new Complete(Content.array(blocks), null, false);
    }

    /** Content blocks plus a structured result. */
    static Complete structured(JsonObject structuredContent, JsonObject... blocks) {
        return new Complete(Content.array(blocks), structuredContent, false);
    }

    static ToolResult inputRequired(JsonObject inputRequests, String requestState) {
        return new InputRequired(inputRequests, requestState);
    }

    /** Asks the client for input ({@link Elicitation}) and has it retry the call with the answers. {@code requestState} may be null; if it is not, sign it ({@link eu.hansolo.mcp.server.McpRequestStates}). */
    static ToolResult ask(InputRequests requests, String requestState) {
        return new InputRequired(requests.toJson(), requestState);
    }

    /** Runs the work as a task for clients that support the extension, inline for the others. */
    static Task task(TaskWork work) {
        return new Task(work, null, false, null);
    }

    /** Follows a job that the tool has already started in another system; {@code handle} is what the tool's {@link ExternalJob} needs to ask about it. */
    static ExternalTask externalTask(JsonObject handle) {
        if (handle == null) {
            throw new IllegalArgumentException("handle is required");
        }
        return new ExternalTask(taskId -> handle, null, false, null);
    }

    /** Starts the job once the task id exists (to use as a correlation key for a callback) and follows it. */
    static ExternalTask externalTask(ExternalStart start) {
        return new ExternalTask(start, null, false, null);
    }

    private static JsonObject textBlock(String text) {
        return Json.createObjectBuilder().add("type", "text").add("text", text).build();
    }
}
