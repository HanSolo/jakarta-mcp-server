package eu.hansolo.mcp.tasks;

import jakarta.json.JsonObject;

import java.time.Instant;
import java.util.function.Consumer;

/**
 * Everything the server stores about one task. Immutable; a change makes a new record with {@code version + 1}, and a
 * {@link TaskStore} replaces a record only if its version is still the one the change was based on (compare and set).
 *
 * @param id              the task id: unguessable, and a bearer token for the stored state
 * @param owner           who created the task ({@code Caller.subject()}, or {@code anonymous}); only the owner may use it
 * @param tool            the name of the tool that created it
 * @param status          the current state
 * @param statusMessage   the latest status message, or null
 * @param createdAt       when the task was created
 * @param lastUpdatedAt   when its state last changed (a lease renewal does not count)
 * @param ttlMs           the retention time the client was told, or null for none
 * @param expiresAt       when the store may drop the task, or null
 * @param pollIntervalMs  the suggested polling interval
 * @param result          the final tool result as sent by {@code tasks/get}, once completed; otherwise null
 * @param error           the JSON-RPC error object ({@code code}, {@code message}), once failed; otherwise null
 * @param inputRequests   every input request the work has made, by key (answered or not); keys are never reused
 * @param inputResponses  the answers received so far, by key
 * @param cancelRequested true once the client asked to cancel
 * @param workerId        the server instance that runs the work, or null
 * @param leaseUntil      until when that instance owns the work; after that another instance may declare the task lost
 * @param attempt         which run of the work this is, starting at 1; grows when another instance restarts the task
 * @param restartable     true if the task may be run again from the start when its worker is lost
 * @param call            the stored tool call ({@code arguments}, {@code caller}, {@code clientCapabilities}, {@code clientInfo}) of a restartable task; otherwise null
 * @param version         starts at 0, grows by one with every change
 */
public record TaskRecord(String id, String owner, String tool, TaskStatus status, String statusMessage, Instant createdAt, Instant lastUpdatedAt, Long ttlMs, Instant expiresAt, long pollIntervalMs, JsonObject result, JsonObject error, JsonObject inputRequests, JsonObject inputResponses, boolean cancelRequested, String workerId, Instant leaseUntil, int attempt, boolean restartable, JsonObject call, long version) {

    public TaskRecord {
        inputRequests  = inputRequests == null ? JsonObject.EMPTY_JSON_OBJECT : inputRequests;
        inputResponses = inputResponses == null ? JsonObject.EMPTY_JSON_OBJECT : inputResponses;
        attempt        = Math.max(1, attempt);
    }

    /** A record that is not restartable, on its first attempt. */
    public TaskRecord(String id, String owner, String tool, TaskStatus status, String statusMessage, Instant createdAt, Instant lastUpdatedAt, Long ttlMs, Instant expiresAt, long pollIntervalMs, JsonObject result, JsonObject error, JsonObject inputRequests, JsonObject inputResponses, boolean cancelRequested, String workerId, Instant leaseUntil, long version) {
        this(id, owner, tool, status, statusMessage, createdAt, lastUpdatedAt, ttlMs, expiresAt, pollIntervalMs, result, error, inputRequests, inputResponses, cancelRequested, workerId, leaseUntil, 1, false, null, version);
    }


    /** A new task: {@code working}, version 0. */
    public static TaskRecord create(String id, String owner, String tool, Instant now, Long ttlMs, long pollIntervalMs, String workerId, Instant leaseUntil) {
        Instant expiresAt = ttlMs == null ? null : now.plusMillis(ttlMs);
        return new TaskRecord(id, owner, tool, TaskStatus.WORKING, null, now, now, ttlMs, expiresAt, pollIntervalMs, null, null, null, null, false, workerId, leaseUntil, 0L);
    }

    /** The same task, marked restartable, with the call to run again. */
    public TaskRecord restartable(JsonObject call) {
        return new TaskRecord(id, owner, tool, status, statusMessage, createdAt, lastUpdatedAt, ttlMs, expiresAt, pollIntervalMs, result, error, inputRequests, inputResponses, cancelRequested, workerId, leaseUntil, attempt, true, call, version);
    }

    /** The input requests that have no answer yet. */
    public JsonObject pendingInputRequests() {
        jakarta.json.JsonObjectBuilder pending = jakarta.json.Json.createObjectBuilder();
        inputRequests.forEach((key, value) -> {
            if (!inputResponses.containsKey(key)) {
                pending.add(key, value);
            }
        });
        return pending.build();
    }

    /**
     * The next version of this record. The editor changes the copy; the version grows by one, and {@code lastUpdatedAt} is set to
     * {@code now} unless the change is only a lease renewal ({@code touch} false).
     */
    public TaskRecord edit(Instant now, boolean touch, Consumer<Editor> changes) {
        Editor editor = new Editor(this);
        changes.accept(editor);
        return editor.build(now, touch);
    }

    public TaskRecord edit(Instant now, Consumer<Editor> changes) {
        return edit(now, true, changes);
    }


    /** The fields a change may touch. */
    public static final class Editor {

        private TaskStatus status;
        private String     statusMessage;
        private JsonObject result;
        private JsonObject error;
        private JsonObject inputRequests;
        private JsonObject inputResponses;
        private boolean    cancelRequested;
        private String     workerId;
        private Instant    leaseUntil;
        private int        attempt;

        private final TaskRecord base;


        private Editor(TaskRecord base) {
            this.base            = base;
            this.status          = base.status;
            this.statusMessage   = base.statusMessage;
            this.result          = base.result;
            this.error           = base.error;
            this.inputRequests   = base.inputRequests;
            this.inputResponses  = base.inputResponses;
            this.cancelRequested = base.cancelRequested;
            this.workerId        = base.workerId;
            this.leaseUntil      = base.leaseUntil;
            this.attempt         = base.attempt;
        }


        public Editor status(TaskStatus status) {
            this.status = status;
            return this;
        }

        public Editor statusMessage(String statusMessage) {
            this.statusMessage = statusMessage;
            return this;
        }

        public Editor result(JsonObject result) {
            this.result = result;
            return this;
        }

        public Editor error(JsonObject error) {
            this.error = error;
            return this;
        }

        public Editor inputRequests(JsonObject inputRequests) {
            this.inputRequests = inputRequests;
            return this;
        }

        public Editor inputResponses(JsonObject inputResponses) {
            this.inputResponses = inputResponses;
            return this;
        }

        public Editor cancelRequested(boolean cancelRequested) {
            this.cancelRequested = cancelRequested;
            return this;
        }

        public Editor lease(String workerId, Instant leaseUntil) {
            this.workerId   = workerId;
            this.leaseUntil = leaseUntil;
            return this;
        }

        /** Starts the next attempt: the number grows by one. */
        public Editor nextAttempt() {
            this.attempt = base.attempt + 1;
            return this;
        }

        private TaskRecord build(Instant now, boolean touch) {
            return new TaskRecord(base.id, base.owner, base.tool, status, statusMessage, base.createdAt, touch ? now : base.lastUpdatedAt, base.ttlMs, base.expiresAt, base.pollIntervalMs, result, error, inputRequests, inputResponses, cancelRequested, workerId, leaseUntil, attempt, base.restartable, base.call, base.version + 1);
        }
    }
}
