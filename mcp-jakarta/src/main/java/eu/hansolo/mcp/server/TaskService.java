package eu.hansolo.mcp.server;

import eu.hansolo.mcp.api.Caller;
import eu.hansolo.mcp.api.InputRequests;
import eu.hansolo.mcp.api.ProgressReporter;
import eu.hansolo.mcp.api.RequestCancelledException;
import eu.hansolo.mcp.api.Scopes;
import eu.hansolo.mcp.api.TaskContext;
import eu.hansolo.mcp.api.Tasks;
import eu.hansolo.mcp.api.ToolCall;
import eu.hansolo.mcp.api.ToolResult;
import eu.hansolo.mcp.protocol.ErrorCodes;
import eu.hansolo.mcp.protocol.McpProtocolException;
import eu.hansolo.mcp.tasks.InMemoryTaskStore;
import eu.hansolo.mcp.tasks.TaskRecord;
import eu.hansolo.mcp.tasks.TaskStatus;
import eu.hansolo.mcp.tasks.TaskStore;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonValue;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The Tasks extension: creates tasks, runs their work, and answers {@code tasks/get}, {@code tasks/update} and {@code tasks/cancel}.
 *
 * <p>The task record lives in a {@link TaskStore}; the work runs on one instance, which renews a lease on the record every
 * {@code mcp.tasks.heartbeat-ms}. An instance that finds a record whose lease ended (its worker stopped without finishing) declares it
 * {@code failed}, or {@code cancelled} if cancellation was requested. The work itself is not restarted: the store makes the record
 * durable, not the execution.
 */
@Singleton
public class TaskService {

    private static final Logger LOG         = Logger.getLogger(TaskService.class.getName());
    private static final int    MAX_RETRIES = 25;
    private static final int    BATCH       = 100;

    private final TaskStore                store;
    private final TaskConfig               config;
    private final McpRegistry              registry;
    private final Clock                    clock;
    private final String                   instanceId = UUID.randomUUID().toString();
    private final SecureRandom             random     = new SecureRandom();
    private final Map<String, Running>     running    = new ConcurrentHashMap<>();
    private final Semaphore                slots;
    private final Executor                 fixedExecutor;

    private Executor                       executor;
    private ExecutorService                ownedExecutor;
    private ScheduledExecutorService       scheduler;


    @Inject
    public TaskService(Instance<TaskStore> stores, TaskConfig config, McpRegistry registry) {
        this(chooseStore(stores, config), config, registry, Clock.systemUTC(), null);
    }

    /** A service with its own store and clock, and optionally its own executor for the work (null: resolve as configured). */
    public TaskService(TaskStore store, TaskConfig config, McpRegistry registry, Clock clock, Executor executor) {
        this.store         = store;
        this.config        = config;
        this.registry      = registry;
        this.clock         = clock;
        this.fixedExecutor = executor;
        this.slots         = new Semaphore(config.maxRunning());
    }


    /** A service for a handler that was built without CDI: the in-memory store and the default settings, extension off. */
    public static TaskService disabled(McpRegistry registry) {
        return new TaskService(new InMemoryTaskStore(), new TaskConfig(false, 3_600_000L, 2_000L, 1024 * 1024, 100, null, 10_000L, 30_000L, 30_000L, 500L, 3_600_000L), registry, Clock.systemUTC(), null);
    }

    private static TaskStore chooseStore(Instance<TaskStore> stores, TaskConfig config) {
        if (!config.isEnabled() || stores == null) {
            return new InMemoryTaskStore();
        }
        List<TaskStore> usable = new ArrayList<>();
        for (TaskStore candidate : stores) {
            if (candidate.available()) {
                usable.add(candidate);
            }
        }
        if (usable.size() > 1) {
            throw new IllegalStateException("More than one TaskStore is available (" + usable.stream().map(s -> s.getClass().getName()).toList() + "); keep exactly one, for example drop either the application's own store or the mcp-jakarta-tasks-jdbc module");
        }
        return usable.isEmpty() ? new InMemoryTaskStore() : usable.get(0);
    }

    /** True if the extension is switched on ({@code mcp.tasks.enabled}). */
    public boolean enabled() {
        return config.isEnabled();
    }

    /** True while tasks live only in this JVM's memory. */
    public boolean usesMemoryStore() {
        return store instanceof InMemoryTaskStore;
    }

    public String instanceId() {
        return instanceId;
    }


    // ---- creating tasks ----

    /**
     * Creates the task, starts its work and returns the {@code CreateTaskResult}. The record is stored before this returns.
     *
     * @param call the original call; its progress reporter is replaced, because the request ends with the response
     */
    public JsonObject create(ToolResult.Task task, ToolCall call) {
        if (!slots.tryAcquire()) {
            throw new McpProtocolException(503, ErrorCodes.INTERNAL_ERROR, "Too many tasks are running on this server", null);
        }
        boolean started = false;
        try {
            start();
            Instant now         = clock.instant();
            Long    ttl         = task.noExpiry() ? null : task.ttlMs() != null ? task.ttlMs() : Long.valueOf(config.ttlMs());
            long    poll        = task.pollIntervalMs() != null ? task.pollIntervalMs() : config.pollIntervalMs();
            TaskRecord record   = TaskRecord.create(newId(), owner(call.caller()), call.name(), now, ttl, poll, instanceId, now.plusMillis(config.leaseMs()));
            try {
                store.create(record);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "The task store refused a new task", e);
                throw McpProtocolException.internal("The task could not be created");
            }
            Running worker = new Running(record.id());
            running.put(record.id(), worker);
            ToolCall detached = new ToolCall(call.name(), call.arguments(), call.clientCapabilities(), call.clientInfo(), call.inputResponses(), call.requestState(), call.caller(), ProgressReporter.NONE, call.trace());
            try {
                executor().execute(() -> run(worker, task, detached));
            } catch (RejectedExecutionException e) {
                running.remove(record.id());
                finish(record.id(), rec -> rec.edit(clock.instant(), edit -> edit.status(TaskStatus.FAILED).error(error(ErrorCodes.INTERNAL_ERROR, "The server could not start the task"))));
                throw McpProtocolException.internal("The task could not be started");
            }
            started = true;
            return taskFields(record).add("resultType", "task").build();
        } finally {
            if (!started) {
                slots.release();
            }
        }
    }

    /** Runs the work on the request thread, for a client that cannot use tasks. Waiting for input is impossible then. */
    public ToolResult runInline(ToolResult.Task task, ToolCall call) throws Exception {
        ToolResult outcome = task.work().run(new InlineContext(call));
        if (outcome instanceof ToolResult.Task) {
            LOG.warning("Tool '" + call.name() + "': a task cannot finish with another task");
            return ToolResult.error("Tool execution failed");
        }
        return outcome;
    }

    private void run(Running worker, ToolResult.Task task, ToolCall call) {
        String id = worker.id;
        try {
            ToolResult outcome;
            try {
                outcome = task.work().run(new WorkerContext(worker, call));
            } catch (RequestCancelledException e) {
                finish(id, rec -> rec.edit(clock.instant(), edit -> edit.status(TaskStatus.CANCELLED)));
                return;
            } catch (McpProtocolException e) {
                finish(id, rec -> rec.edit(clock.instant(), edit -> edit.status(TaskStatus.FAILED).error(error(e.code(), e.getMessage()))));
                return;
            } catch (Exception e) {
                LOG.log(Level.WARNING, "The work of task " + abbreviate(id) + " (tool '" + call.name() + "') failed", e);
                outcome = ToolResult.error("Tool execution failed");
            }
            if (!(outcome instanceof ToolResult.Complete complete)) {
                LOG.warning("Tool '" + call.name() + "': the work of a task must end with a complete result");
                finish(id, rec -> rec.edit(clock.instant(), edit -> edit.status(TaskStatus.FAILED).error(error(ErrorCodes.INTERNAL_ERROR, "The task did not produce a result"))));
                return;
            }
            JsonObject result = McpRequestHandler.toResult(complete);
            if (result.toString().getBytes(StandardCharsets.UTF_8).length > config.maxResultBytes()) {
                finish(id, rec -> rec.edit(clock.instant(), edit -> edit.status(TaskStatus.FAILED).error(error(ErrorCodes.INTERNAL_ERROR, "The result is larger than mcp.tasks.max-result-bytes (" + config.maxResultBytes() + ")"))));
                return;
            }
            finish(id, rec -> rec.edit(clock.instant(), edit -> edit.status(TaskStatus.COMPLETED).result(result)));
        } catch (Throwable t) {
            LOG.log(Level.SEVERE, "Task " + abbreviate(id) + " ended abnormally", t);
            try {
                finish(id, rec -> rec.edit(clock.instant(), edit -> edit.status(TaskStatus.FAILED).error(error(ErrorCodes.INTERNAL_ERROR, "The task ended abnormally"))));
            } catch (RuntimeException ignored) {
                // the lease will run out and a sweep declares the task lost
            }
        } finally {
            running.remove(id);
            slots.release();
        }
    }

    /** Applies a terminal change unless the task is already terminal or gone. */
    private void finish(String id, UnaryOperator<TaskRecord> change) {
        mutate(id, rec -> rec.status().isTerminal() ? null : change.apply(rec));
    }


    // ---- the three methods ----

    /** The {@code tasks/get} result. */
    public JsonObject get(String taskId, Caller caller) {
        start();
        TaskRecord rec = lookup(taskId, caller);
        JsonObjectBuilder out = taskFields(rec).add("resultType", "complete");
        switch (rec.status()) {
            case COMPLETED -> out.add("result", rec.result());
            case FAILED -> out.add("error", rec.error());
            case INPUT_REQUIRED -> out.add("inputRequests", rec.pendingInputRequests());
            default -> {
            }
        }
        return out.build();
    }

    /** {@code tasks/update}: stores answers for open input requests; unknown and already answered keys are ignored. */
    public void update(String taskId, Caller caller, JsonObject inputResponses) {
        TaskRecord rec = lookup(taskId, caller);
        mutate(rec.id(), current -> {
            if (current.status().isTerminal()) {
                return null;
            }
            JsonObjectBuilder merged = Json.createObjectBuilder(current.inputResponses());
            boolean changed = false;
            for (Map.Entry<String, JsonValue> answer : inputResponses.entrySet()) {
                if (current.inputRequests().containsKey(answer.getKey()) && !current.inputResponses().containsKey(answer.getKey())) {
                    merged.add(answer.getKey(), answer.getValue());
                    changed = true;
                }
            }
            return changed ? current.edit(clock.instant(), edit -> edit.inputResponses(merged.build())) : null;
        });
        Running worker = running.get(rec.id());
        if (worker != null) {
            worker.wake();
        }
    }

    /** {@code tasks/cancel}: asks the work to stop. The task becomes {@code cancelled} when the work notices; it may also finish regardless. */
    public void cancel(String taskId, Caller caller) {
        TaskRecord rec = lookup(taskId, caller);
        mutate(rec.id(), current -> current.status().isTerminal() || current.cancelRequested() ? null : current.edit(clock.instant(), edit -> edit.cancelRequested(true)));
        Running worker = running.get(rec.id());
        if (worker != null) {
            worker.cancelled = true;
            worker.wake();
        }
    }

    /** Finds the task for this caller. Unknown, foreign, expired and not-permitted tasks are indistinguishable. */
    private TaskRecord lookup(String taskId, Caller caller) {
        Instant now = clock.instant();
        Optional<TaskRecord> found = taskId == null || taskId.length() > 128 ? Optional.empty() : store.find(taskId);
        TaskRecord rec = found.filter(r -> r.owner().equals(owner(caller))).filter(r -> r.expiresAt() == null || r.expiresAt().isAfter(now)).filter(r -> registry.find(r.tool()).map(tool -> Scopes.satisfiesAll(caller.scopes(), tool.requiredScopes())).orElse(true)).orElse(null);
        if (rec == null) {
            throw McpProtocolException.invalidArguments("Unknown task");
        }
        return rec;
    }


    // ---- upkeep ----

    /** Renews the lease of every task this instance runs and picks up cancellation and loss. Runs on a timer; public for tests. */
    public void heartbeat() {
        for (Running worker : running.values()) {
            try {
                Instant now = clock.instant();
                Optional<TaskRecord> rec = mutate(worker.id, current -> current.status().isTerminal() ? null : current.edit(now, false, edit -> edit.lease(instanceId, now.plusMillis(config.leaseMs()))));
                if (rec.isEmpty() || rec.get().status().isTerminal() || rec.get().cancelRequested()) {
                    worker.cancelled = true;
                    worker.wake();
                }
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Could not renew the lease of task " + abbreviate(worker.id), e);
            }
        }
    }

    /** Declares tasks without a live worker lost and drops expired tasks. Runs on a timer; public for tests. */
    public void sweep() {
        try {
            Instant now = clock.instant();
            for (String id : store.leaseExpired(now, BATCH)) {
                mutate(id, rec -> rec.status().isTerminal() || rec.leaseUntil() == null || !rec.leaseUntil().isBefore(now) ? null : rec.edit(now, edit -> {
                    if (rec.cancelRequested()) {
                        edit.status(TaskStatus.CANCELLED);
                    } else {
                        edit.status(TaskStatus.FAILED).error(error(ErrorCodes.INTERNAL_ERROR, "The server instance running this task stopped before it finished"));
                    }
                }));
            }
            for (String id : store.expired(now, BATCH)) {
                store.delete(id);
                Running worker = running.get(id);
                if (worker != null) {
                    worker.cancelled = true;
                    worker.wake();
                }
            }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "The task sweep failed", e);
        }
    }

    /**
     * Starts the heartbeat and the sweep of this instance. Idempotent. Every instance with the extension on must run it, also one that never
     * creates a task itself, because the sweep is what declares the tasks of a dead instance failed.
     */
    public synchronized void start() {
        if (scheduler != null || !config.isEnabled()) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mcp-tasks-upkeep");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::heartbeat, config.heartbeatMs(), config.heartbeatMs(), TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(this::sweep, config.sweepMs(), config.sweepMs(), TimeUnit.MILLISECONDS);
    }

    private synchronized Executor executor() {
        if (executor == null) {
            executor = fixedExecutor != null ? fixedExecutor : lookupExecutor();
            if (executor == null) {
                ownedExecutor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("mcp-task-", 0).factory());
                executor      = ownedExecutor;
                LOG.info("Task work runs on virtual threads (no managed executor found)");
            }
        }
        return executor;
    }

    private Executor lookupExecutor() {
        String[] names = config.executor() != null ? new String[] { config.executor() } : new String[] { "java:comp/DefaultManagedExecutorService" };
        for (String name : names) {
            try {
                Object found = new InitialContext().lookup(name);
                if (found instanceof Executor candidate) {
                    LOG.info("Task work runs on the managed executor " + name);
                    return candidate;
                }
                LOG.warning("mcp.tasks.executor '" + name + "' is not an Executor (" + found.getClass().getName() + ")");
            } catch (NamingException | RuntimeException e) {
                if (config.executor() != null) {
                    LOG.log(Level.WARNING, "mcp.tasks.executor '" + name + "' could not be looked up; using virtual threads", e);
                }
            }
        }
        return null;
    }

    /** Stops the upkeep thread and ends the tasks this instance still runs as failed, so clients need not wait for a lease to run out. */
    @PreDestroy
    public synchronized void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        for (Running worker : running.values()) {
            worker.cancelled = true;
            worker.wake();
            try {
                finish(worker.id, rec -> rec.edit(clock.instant(), edit -> edit.status(TaskStatus.FAILED).error(error(ErrorCodes.INTERNAL_ERROR, "The server stopped before the task finished"))));
            } catch (RuntimeException e) {
                LOG.log(Level.FINE, "Could not close task " + abbreviate(worker.id), e);
            }
        }
        if (ownedExecutor != null) {
            ownedExecutor.shutdownNow();
        }
    }


    // ---- helpers ----

    private Optional<TaskRecord> mutate(String id, UnaryOperator<TaskRecord> change) {
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            Optional<TaskRecord> found = store.find(id);
            if (found.isEmpty()) {
                return Optional.empty();
            }
            TaskRecord next = change.apply(found.get());
            if (next == null) {
                return found;
            }
            if (store.replace(found.get(), next)) {
                return Optional.of(next);
            }
        }
        throw new IllegalStateException("Task " + abbreviate(id) + " keeps changing; gave up after " + MAX_RETRIES + " attempts");
    }

    private JsonObjectBuilder taskFields(TaskRecord rec) {
        JsonObjectBuilder out = Json.createObjectBuilder().add("taskId", rec.id()).add("status", rec.status().wire());
        if (rec.statusMessage() != null) {
            out.add("statusMessage", rec.statusMessage());
        }
        out.add("createdAt", rec.createdAt().toString()).add("lastUpdatedAt", rec.lastUpdatedAt().toString());
        if (rec.ttlMs() == null) {
            out.add("ttlMs", JsonValue.NULL);
        } else {
            out.add("ttlMs", rec.ttlMs());
        }
        return out.add("pollIntervalMs", rec.pollIntervalMs());
    }

    private static JsonObject error(int code, String message) {
        return Json.createObjectBuilder().add("code", code).add("message", message).build();
    }

    private String newId() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Whom a task belongs to. With authentication off everyone is the same caller, and the id's secrecy is the only protection. */
    static String owner(Caller caller) {
        return caller.subject().isEmpty() ? "anonymous" : caller.subject();
    }

    private static String abbreviate(String id) {
        return id.length() <= 8 ? id : id.substring(0, 8) + "...";
    }


    /** What this instance knows about a task it runs: a cancel flag and a way to wake the work that waits for input. */
    private static final class Running {

        private final String        id;
        private final ReentrantLock lock    = new ReentrantLock();
        private final Condition     changed = lock.newCondition();

        private volatile boolean    cancelled;
        private boolean             woken;


        private Running(String id) {
            this.id = id;
        }


        private void wake() {
            lock.lock();
            try {
                woken = true;
                changed.signalAll();
            } finally {
                lock.unlock();
            }
        }

        private void await(long millis) {
            lock.lock();
            try {
                if (!woken) {
                    changed.await(millis, TimeUnit.MILLISECONDS);
                }
                woken = false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RequestCancelledException("Interrupted while waiting for input", e);
            } finally {
                lock.unlock();
            }
        }
    }


    private final class WorkerContext implements TaskContext {

        private final Running worker;
        private final ToolCall call;


        private WorkerContext(Running worker, ToolCall call) {
            this.worker = worker;
            this.call   = call;
        }


        @Override
        public String taskId() {
            return worker.id;
        }

        @Override
        public ToolCall call() {
            return call;
        }

        @Override
        public void status(String message) {
            try {
                Optional<TaskRecord> rec = mutate(worker.id, current -> current.status().isTerminal() ? null : current.edit(clock.instant(), edit -> edit.statusMessage(message)));
                if (rec.isEmpty()) {
                    worker.cancelled = true;
                }
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Could not store the status of task " + abbreviate(worker.id), e);
            }
        }

        @Override
        public boolean isCancelled() {
            return worker.cancelled;
        }

        @Override
        public JsonObject ask(InputRequests requests) {
            Set<String> keys     = requests.keys();
            JsonObject  asked    = requests.toJson();
            Optional<TaskRecord> stored = mutate(worker.id, current -> {
                if (current.status().isTerminal()) {
                    return null;
                }
                JsonObjectBuilder merged = Json.createObjectBuilder(current.inputRequests());
                for (String key : keys) {
                    if (current.inputRequests().containsKey(key)) {
                        throw new IllegalArgumentException("Input request key '" + key + "' was already used in this task");
                    }
                    merged.add(key, asked.get(key));
                }
                return current.edit(clock.instant(), edit -> edit.inputRequests(merged.build()).status(TaskStatus.INPUT_REQUIRED));
            });
            if (stored.isEmpty() || stored.get().status().isTerminal()) {
                worker.cancelled = true;
                throw new RequestCancelledException("The task has ended", null);
            }
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.inputTimeoutMs());
            while (true) {
                if (worker.cancelled) {
                    throw new RequestCancelledException("The task was cancelled", null);
                }
                Optional<TaskRecord> now = store.find(worker.id);
                if (now.isEmpty() || now.get().status().isTerminal() || now.get().cancelRequested()) {
                    worker.cancelled = true;
                    throw new RequestCancelledException("The task was cancelled or has expired", null);
                }
                JsonObject responses = now.get().inputResponses();
                if (keys.stream().allMatch(responses::containsKey)) {
                    mutate(worker.id, current -> current.status() == TaskStatus.INPUT_REQUIRED && current.pendingInputRequests().isEmpty() ? current.edit(clock.instant(), edit -> edit.status(TaskStatus.WORKING)) : null);
                    JsonObjectBuilder answers = Json.createObjectBuilder();
                    keys.forEach(key -> answers.add(key, responses.get(key)));
                    return answers.build();
                }
                if (System.nanoTime() > deadline) {
                    throw new McpProtocolException(200, ErrorCodes.INTERNAL_ERROR, "Timed out waiting for the client's input", null);
                }
                worker.await(config.inputPollMs());
            }
        }
    }


    private static final class InlineContext implements TaskContext {

        private final ToolCall call;


        private InlineContext(ToolCall call) {
            this.call = call;
        }


        @Override
        public String taskId() {
            return "";
        }

        @Override
        public ToolCall call() {
            return call;
        }

        @Override
        public void status(String message) {
            // nobody can poll an inline call
        }

        @Override
        public boolean isCancelled() {
            return call.isCancelled();
        }

        @Override
        public JsonObject ask(InputRequests requests) {
            throw McpProtocolException.missingClientExtension(Tasks.EXTENSION_ID);
        }
    }
}
