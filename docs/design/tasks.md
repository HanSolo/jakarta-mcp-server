# Design note: Tasks extension (`io.modelcontextprotocol/tasks`), planned for 0.6.0

Status: phase 1 (core, in-memory store) implemented in 0.6.0-SNAPSHOT; the decisions below are settled (see the end). Sources: [overview](https://modelcontextprotocol.io/extensions/tasks/overview.md) and [SEP-2663](https://modelcontextprotocol.io/seps/2663-tasks-extension) (Final, but "preserved as a historical record"; the normative text is in the `ext-tasks` repository, which could not be fetched when this note was written, so field names below are from the SEP).

## What the specification asks of a server

- Only `tools/call` can become a task. The **server decides per request**; the client opts in by declaring `io.modelcontextprotocol/tasks` in its per-request capabilities. A task must never be returned to a client that did not declare it.
- Task creation returns `{resultType: "task", taskId, status, statusMessage?, createdAt, lastUpdatedAt, ttlMs (number|null), pollIntervalMs?}`. **The task must be durably created before the response is sent** (a `tasks/get` for that id must resolve).
- `tasks/get` (idempotent read) returns the task plus `result` (completed), `error` (failed, a JSON-RPC error), `inputRequests` (input_required). `resultType` is `"complete"` on this response.
- `tasks/update` takes `inputResponses`, answers with an empty ack, ignores unknown or already answered keys. Keys in `inputRequests` are unique over the whole life of the task.
- `tasks/cancel` is an ack only; cancellation is cooperative and eventually consistent.
- States: `working`, `input_required`, `completed`, `failed`, `cancelled`; the last three are terminal. `failed` is only for JSON-RPC errors; a tool result with `isError: true` is `completed`.
- Headers: `Mcp-Method` and `Mcp-Name` (= `params.taskId`) on all three methods. Unknown task: `-32602`. Non-declaring client calling `tasks/*`: `-32021`.
- Security: unguessable ids; **authorization checked on every task request**; no `tasks/list`. No `notifications/progress` or `notifications/message` on tasks. Optional `notifications/tasks` through `subscriptions/listen`.
- The 2025-11-25 experimental tasks are not wire-compatible and are out of scope (no legacy clients).

## The uncomfortable part: durable state is not durable execution

The specification demands that the *task record* survives; it says nothing a library can use to make the *work* survive. A Jakarta EE application that starts a thread for a task loses that work when the JVM stops, whatever the store says. So the design separates the two:

- **`TaskStore`**: where task records live (durable, shared by all instances).
- **A runner** that executes work on one instance, with a lease. A task whose lease expires without a heartbeat is marked `failed` ("worker lost") by whichever instance notices, unless the application declared the work restartable.

The library can offer a correct store and a correct state machine. It cannot offer "your job survives a restart" unless the job lives in something durable (an external job system, a database queue). The design therefore also supports tasks whose work is *external* (see "Two ways to produce a task").

[Certain] An in-memory store is not "durable" in the sense of the specification. [Likely] it is still worth shipping as the default for a single instance, with a startup warning like the one for `mcp.request-state.secret`.

## API sketch

```java
// A tool decides at call time.
public ToolResult call(ToolCall call) {
    if (!call.clientSupportsTasks()) {
        return runNow(call);                       // or ToolResult.task(...) and let the server run it inline
    }
    return ToolResult.task(ctx -> {                // TaskWork: (TaskContext) -> ToolResult, throws McpProtocolException => failed
        ctx.status("Fetching 1 of 3");             // statusMessage, the only progress channel
        ToolResult input = ctx.ask(InputRequests.of("confirm", Elicitation.form("Proceed?").bool("ok").build()));  // -> input_required, blocks until tasks/update or timeout
        if (ctx.isCancelled()) return ToolResult.text("stopped");
        return ToolResult.text("done");
    });
}
```

- `ToolResult.task(TaskWork)` is a third `ToolResult` variant. If the client did not declare the extension, the server runs the work inline on the request thread and returns the plain result, so a tool is written once. [Likely] this is the least surprising behaviour; the alternative (an error) breaks tools for every client without Tasks.
- `TaskContext`: `taskId()`, `call()` (the original `ToolCall`, so arguments, caller and trace context are available), `status(String)`, `ask(InputRequests)`, `isCancelled()`.
- Optional per-tool settings through `McpToolHandler` defaults or `@McpTool(task = ...)`: `ttlMs`, `pollIntervalMs`.

### Two ways to produce a task

1. **Managed work** (above): the library runs `TaskWork` on an executor and writes the outcome to the store.
2. **External job**: the tool creates the job in its own system and calls `ToolResult.externalTask(handle)` with a `TaskHandle` the application implements (`poll(taskId) -> TaskState`, `cancel(taskId)`, `answer(taskId, inputResponses)`). The library stores only the mapping and the owner. This is the "wrap an API that already has job ids" case from the specification, and the only one that gives real durable execution.

[Guessing] most users start with (1); (2) can follow later without breaking (1).

## `TaskStore` SPI

```java
public interface TaskStore {
    TaskRecord create(NewTask task);                        // strongly consistent: visible to get() on return
    Optional<TaskRecord> get(String taskId, String owner);  // empty also when the owner differs
    boolean update(String taskId, long expectedVersion, TaskChange change);   // compare and set
    void requestCancel(String taskId, String owner);
    void putInputResponses(String taskId, String owner, JsonObject responses);
    List<String> expired(Instant now, int limit);           // for the sweeper
    void delete(String taskId);
}
```

A `TaskRecord` holds: id, owner, tool name, status, statusMessage, createdAt, lastUpdatedAt, ttlMs, pollIntervalMs, result (JSON, size-capped), error (JSON), inputRequests, answered keys, `cancelRequested`, lease (`workerId`, `leaseUntil`), and a `version` for compare-and-set.

Implementations:
- `InMemoryTaskStore` (default): `ConcurrentHashMap`, single instance only.
- `JdbcTaskStore`: plain JDBC through a `DataSource` (JNDI name from `mcp.tasks.datasource`), one table, DDL documented for PostgreSQL and H2. [Likely] plain JDBC is better than JPA here: no persistence-unit setup for the user, compare-and-set maps to one `UPDATE ... WHERE version = ?`.
- Applications may provide their own store as a CDI bean (Redis, Cosmos, ...).

## Behaviour that follows

- **Id**: 32 bytes from `SecureRandom`, base64url. The id is a bearer token for stored state, so it also goes through the owner check.
- **Owner**: `Caller.subject()`. With authentication off every task has the owner `anonymous`; then the id's entropy is the only protection. Documented, like the other "auth off" caveats.
- **Cancel across instances**: `tasks/cancel` sets `cancelRequested` in the store; the worker sees it at `ctx.isCancelled()` (a local flag, refreshed from the store at the heartbeat interval). A task is only moved to `cancelled` by the worker or, when the lease has expired, by the sweeper.
- **Input**: `ctx.ask` writes `inputRequests` and sets `input_required`; `tasks/update` stores responses; the worker polls the store (and is woken immediately when the update lands on the same instance). Keys are checked for reuse. Waiting is bounded by the TTL.
- **TTL**: default `mcp.tasks.ttl-ms` = 1 hour; `null` (unlimited) only if the application asks for it per tool. A sweeper (virtual thread, once a minute) marks overdue tasks `failed` and deletes them after a grace period.
- **Result size**: results are capped (`mcp.tasks.max-result-bytes`, default 1 MiB); beyond that the task fails with a clear message instead of filling the store.
- **Routing**: `Mcp-Name` must equal `params.taskId` for the three methods (a new case in the existing header check). Any instance can answer `tasks/get` from the store; a load balancer may still prefer the owner.
- **Threading**: `ManagedExecutorService` (`java:comp/DefaultManagedExecutorService`) when present, so security and CDI context propagate; virtual threads otherwise. [Likely] the managed executor matters for tools that use container resources (JPA, JTA), so it is the default when found.
- **MRTR before tasks**: the specification says to resolve any `InputRequiredResult` exchanges synchronously and *then* create the task; that already works with the existing `ToolResult.ask`.
- **Errors**: unknown/foreign/expired task → `-32602` with the same message for all three (no existence oracle); client without the extension → `-32021` through the existing `missingClientExtension`.
- **Notifications** (`notifications/tasks` on `subscriptions/listen`): phase 3. The filter field is not in the text read so far.

## Plan

1. **Core** (one release step): API (`ToolResult.task`, `TaskContext`, `TaskWork`), `TaskStore` + `InMemoryTaskStore`, runner with lease, `tasks/get|update|cancel`, headers, inline fallback, config, unit tests, a fixture tool and `TasksIT` (create, poll, input, cancel, expiry, foreign caller).
2. **`JdbcTaskStore`** and a two-instance IT (two server containers plus a PostgreSQL container: create on A, poll on B, cancel on B while A works).
3. **`notifications/tasks`**, external-job handle (`TaskHandle`), conformance scenarios.

## Decisions I need from you

1. **JDBC store in the main jar or a separate module** (`mcp-jakarta-tasks-jdbc`)? [Likely] separate is cleaner for users who never need it; the cost is one more artifact to release.
2. **Inline fallback** for clients that did not declare the extension (my recommendation) or an error?
3. **Default executor**: managed executor when found (my recommendation) or always virtual threads?
4. **Worker lost**: mark `failed` (my default) or allow `restartable = true` on a task so another instance may re-run it?
5. Whether to ship phase 1 with the in-memory store alone as 0.6.0, or hold 0.6.0 until the JDBC store and two-instance IT pass. [Likely] hold: a Tasks feature that loses everything on restart is easy to misuse, and the README would have to say so on the first line.

## Risks

- The normative `ext-tasks` text was not read; the SEP is marked as a historical record. Field names may have moved.
- The 9 `tasks-*` conformance scenarios are listed in `expected-failures.yml`. Implementing the extension will probably flip some of them, and their fixture requirements are unknown (the same situation as `test_custom_header`).
- Compare-and-set on `version` is simple; heartbeat, lease expiry and the sweeper are where the bugs will be. They need tests with a controllable clock before any IT.

## Decisions taken (2026-11-09)

1. The JDBC store goes in a separate module (`mcp-jakarta-tasks-jdbc`).
2. Clients that did not declare the extension get the plain result (inline fallback).
3. The work runs on a managed executor when one is found, else on virtual threads. The lookup order is `mcp.tasks.executor`, then `java:comp/DefaultManagedExecutorService`, then virtual threads.
4. A task whose worker is lost is marked `failed` (`cancelled` if cancellation was pending). No restart.
5. 0.6.0 is held until the JDBC store and the two-instance integration test pass.

Differences between this note and the code: the store SPI is `create` / `find` / `replace` (compare and set on `version`) / `leaseExpired` / `expired` / `delete`, and the owner check lives in `TaskService`, not in the store; an expired task is deleted (not first marked failed); `ToolResult.task(...)` carries `ttlMs`, `noExpiry` and `pollIntervalMs` as options.
