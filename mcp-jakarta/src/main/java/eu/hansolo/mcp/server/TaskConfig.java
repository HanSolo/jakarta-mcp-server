package eu.hansolo.mcp.server;

import jakarta.inject.Singleton;

/**
 * Settings of the Tasks extension, read like all others (system property first, then environment variable).
 *
 * <ul>
 *   <li>{@code mcp.tasks.enabled}: announce {@code io.modelcontextprotocol/tasks} and serve {@code tasks/get|update|cancel}. Default false;
 *       while it is off, a tool that returns {@code ToolResult.task(...)} runs inline for every client.</li>
 *   <li>{@code mcp.tasks.ttl-ms}: how long a task is kept, counted from its creation. Default 3600000 (1 hour).</li>
 *   <li>{@code mcp.tasks.poll-interval-ms}: the polling interval suggested to clients. Default 2000.</li>
 *   <li>{@code mcp.tasks.max-result-bytes}: largest stored result; a bigger one fails the task. Default 1048576.</li>
 *   <li>{@code mcp.tasks.max-running}: most tasks one instance runs at once. Default 100.</li>
 *   <li>{@code mcp.tasks.executor}: JNDI name of the executor for the work. Default: {@code java:comp/DefaultManagedExecutorService}, then
 *       JDK virtual threads.</li>
 *   <li>{@code mcp.tasks.heartbeat-ms}, {@code mcp.tasks.lease-ms}: how often a running task renews its lease (default 10000) and how long a
 *       lease lasts without renewal (default 30000). A task whose lease ended is declared failed ("lost").</li>
 *   <li>{@code mcp.tasks.sweep-ms}: how often expired and lost tasks are looked for. Default 30000.</li>
 *   <li>{@code mcp.tasks.input-poll-ms}, {@code mcp.tasks.input-timeout-ms}: how often work that waits for input looks for the answer
 *       (default 500) and how long it waits at most (default 3600000).</li>
 *   <li>{@code mcp.tasks.notify-poll-ms}: how often a {@code subscriptions/listen} stream that watches tasks looks in the store for changes
 *       made on other instances (default 2000); changes made on this instance are sent at once.</li>
 *   <li>{@code mcp.tasks.max-attempts}: how many times a restartable task is run at most, the first run included (default 3). 1 switches
 *       restarts off.</li>
 * </ul>
 *
 * <p><b>Internal class, stable settings.</b> The settings below are part of the stable configuration; the class that reads them is not,
 * and applications should not call it.
 */
@Singleton
public class TaskConfig {

    private final boolean enabled;
    private final long    ttlMs;
    private final long    pollIntervalMs;
    private final int     maxResultBytes;
    private final int     maxRunning;
    private final String  executor;
    private final long    heartbeatMs;
    private final long    leaseMs;
    private final long    sweepMs;
    private final long    inputPollMs;
    private final long    inputTimeoutMs;
    private final long    notifyPollMs;
    private final int     maxAttempts;


    public TaskConfig() {
        this(Settings.getBoolean("mcp.tasks.enabled", false), Settings.getLong("mcp.tasks.ttl-ms", 3_600_000L), Settings.getLong("mcp.tasks.poll-interval-ms", 2_000L), Settings.getInt("mcp.tasks.max-result-bytes", 1024 * 1024), Settings.getInt("mcp.tasks.max-running", 100), Settings.get("mcp.tasks.executor"), Settings.getLong("mcp.tasks.heartbeat-ms", 10_000L), Settings.getLong("mcp.tasks.lease-ms", 30_000L), Settings.getLong("mcp.tasks.sweep-ms", 30_000L), Settings.getLong("mcp.tasks.input-poll-ms", 500L), Settings.getLong("mcp.tasks.input-timeout-ms", 3_600_000L), Settings.getLong("mcp.tasks.notify-poll-ms", 2_000L), Settings.getInt("mcp.tasks.max-attempts", 3));
    }

    public TaskConfig(boolean enabled, long ttlMs, long pollIntervalMs, int maxResultBytes, int maxRunning, String executor, long heartbeatMs, long leaseMs, long sweepMs, long inputPollMs, long inputTimeoutMs) {
        this(enabled, ttlMs, pollIntervalMs, maxResultBytes, maxRunning, executor, heartbeatMs, leaseMs, sweepMs, inputPollMs, inputTimeoutMs, 2_000L);
    }

    public TaskConfig(boolean enabled, long ttlMs, long pollIntervalMs, int maxResultBytes, int maxRunning, String executor, long heartbeatMs, long leaseMs, long sweepMs, long inputPollMs, long inputTimeoutMs, long notifyPollMs) {
        this(enabled, ttlMs, pollIntervalMs, maxResultBytes, maxRunning, executor, heartbeatMs, leaseMs, sweepMs, inputPollMs, inputTimeoutMs, notifyPollMs, 3);
    }

    public TaskConfig(boolean enabled, long ttlMs, long pollIntervalMs, int maxResultBytes, int maxRunning, String executor, long heartbeatMs, long leaseMs, long sweepMs, long inputPollMs, long inputTimeoutMs, long notifyPollMs, int maxAttempts) {
        this.maxAttempts    = Math.max(1, maxAttempts);
        this.notifyPollMs   = Math.max(20L, notifyPollMs);
        this.enabled        = enabled;
        this.ttlMs          = Math.max(1L, ttlMs);
        this.pollIntervalMs = Math.max(1L, pollIntervalMs);
        this.maxResultBytes = Math.max(1024, maxResultBytes);
        this.maxRunning     = Math.max(1, maxRunning);
        this.executor       = executor != null && !executor.isBlank() ? executor.trim() : null;
        this.heartbeatMs    = Math.max(10L, heartbeatMs);
        this.leaseMs        = Math.max(this.heartbeatMs * 2, leaseMs);
        this.sweepMs        = Math.max(10L, sweepMs);
        this.inputPollMs    = Math.max(5L, inputPollMs);
        this.inputTimeoutMs = Math.max(1L, inputTimeoutMs);
    }


    /** The defaults with the extension switched on. */
    public static TaskConfig enabled() {
        return new TaskConfig(true, 3_600_000L, 2_000L, 1024 * 1024, 100, null, 10_000L, 30_000L, 30_000L, 500L, 3_600_000L);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public long ttlMs() {
        return ttlMs;
    }

    public long pollIntervalMs() {
        return pollIntervalMs;
    }

    public int maxResultBytes() {
        return maxResultBytes;
    }

    public int maxRunning() {
        return maxRunning;
    }

    /** JNDI name of the executor for the work, or null for the default lookup. */
    public String executor() {
        return executor;
    }

    public long heartbeatMs() {
        return heartbeatMs;
    }

    public long leaseMs() {
        return leaseMs;
    }

    public long sweepMs() {
        return sweepMs;
    }

    public long inputPollMs() {
        return inputPollMs;
    }

    public long inputTimeoutMs() {
        return inputTimeoutMs;
    }

    /** How often a {@code subscriptions/listen} stream looks in the task store for changes made by other instances. */
    public long notifyPollMs() {
        return notifyPollMs;
    }

    /** Most runs of a restartable task, the first included. */
    public int maxAttempts() {
        return maxAttempts;
    }
}
