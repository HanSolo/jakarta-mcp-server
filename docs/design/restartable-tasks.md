# Design note: restartable tasks (0.8.0)

Status: decided 2026-11-11 and implemented. Decisions: per-task declaration only, storing the call is accepted, the schema change is documented as `ALTER TABLE` (no automatic migration), 3 attempts. The builder method is `withRestartable()` (the record accessor is `restartable()`), and `TaskContext.attempt()` exists. It reopens decision 4 of [tasks.md](tasks.md) ("worker lost = `failed`, no restart").

## The uncomfortable part first

A restart cannot re-run "the work" that was running. The work is a `TaskWork` lambda that the tool method created from its arguments; it lives in the memory of the instance that died, and no other instance has it. [Certain] (`TaskService.create` keeps it only in the `run(...)` closure.)

So a restart has to **call the tool again** on the surviving instance, with the stored original call, and let it return a fresh `ToolResult.Task`. That has three consequences the earlier sketch ("`restartable = true` on a task") did not show:

1. The record must store the call: tool name, arguments, caller (subject, scopes, claims), client capabilities and client info. Arguments are therefore written to the task store, which they are not today. For the JDBC store that means personal data in a table, under the task's TTL.
2. The restarted call runs with the scopes of the original token, whose token has probably expired by then. The library does not validate the token again; it trusts the stored caller. This is a deliberate choice and must be documented, because a revoked token still finishes its restarted work.
3. The tool must be safe to call twice with the same arguments. The library cannot check that. Restart is **at-least-once**: a task may run twice in part (the dead instance may have done half of it) and, with a long GC pause, even concurrently for a short while (see "Fencing").

## Decisions proposed

| # | Question | Proposal | Confidence |
|---|---|---|---|
| a | What "restartable" promises | At-least-once re-execution of the tool call from the start. No checkpoints, no resume. The author owns idempotency. | [Likely] |
| b | Where it is declared | Per task, on the result: `ToolResult.task(work).withRestartable()` (default off), because only the tool knows whether its work is idempotent for these arguments. An annotation on `@McpTool` would be a blanket statement about every call. | [Likely] |
| c | `input_required` when the waiting instance dies | Restart from the beginning, discard the open requests and the stored answers, status back to `working`. The client sees the status change in `tasks/get` and in `notifications/tasks`, and is asked again if the work asks again. | [Likely] |
| d | Retry limit | `mcp.tasks.max-attempts`, default 3 (the first run counts). When the limit is reached the task ends `failed` with "worker lost" as today. A task that kills its instance every time must not be restarted forever. | [Certain] that a limit is needed, [Guessing] on 3 |

### On (c), the key reuse rule

`ctx.ask` rejects a key that was used before in the same task. After a restart the work asks for the same keys again, so the stored `inputRequests` and `inputResponses` are cleared when a new attempt starts. The rule "a key is never reused" then holds per attempt. A client that answered the first attempt's `confirm` and now sees `confirm` open again is correct behaviour, and the status message says why ("restarted after the server instance stopped, attempt 2").

I considered keeping the old answers and feeding them to the new attempt. I would not: the second run may ask different questions, and replaying an answer to a question that was asked in another context is the kind of bug nobody finds.

## What changes in the code

- `ToolResult.Task`: a `restartable` boolean and `withRestartable()`; `TaskContext.attempt()` (default 1) so the work can see that it is a re-run.
- `TaskRecord`: new fields `attempt` (int, starts at 1), `restartable` (boolean) and `call` (a JSON object: arguments, caller, client capabilities, client info; stored only when `restartable`). Non-restartable tasks store nothing new.
- `TaskStore` SPI: no new method. `leaseExpired` and `replace` are enough, the claim is a compare-and-set. JDBC gets three columns (`attempt`, `restartable`, `call_json`); the DDL for a new table changes, and **existing tables need an `ALTER TABLE`**, which `mcp.tasks.jdbc.create-schema` does not do today. Decided: document the three statements (README, CHANGELOG, Javadoc of `JdbcTaskStore`); no automatic migration.
- `TaskService.sweep()`: for a record with an expired lease that is `restartable`, not `cancelRequested` and `attempt < maxAttempts`, claim it with one `replace`: `workerId = this instance`, `attempt + 1`, new lease, status `working`, inputs cleared. Whoever wins the compare-and-set runs it; the others get `false` and move on. Everything else is declared lost as before.
- Running the claim: rebuild the `ToolCall` from `call`, invoke the registered tool through the same path `tools/call` uses, and expect a `ToolResult.Task`. If the tool now returns a complete result, store it as the task's result; if it returns `InputRequired` or anything else, fail the task (a restart cannot go back to the client).
- `close()` (graceful shutdown): a restartable task gets its lease set to now instead of being failed, so a surviving instance picks it up at once without waiting for the lease. Non-restartable tasks are failed as today.
- Config: `mcp.tasks.max-attempts` (default 3).

## Fencing

The heartbeat renews the lease of every task in `running` without checking who owns the record. With restarts that becomes wrong: an instance that was paused for longer than the lease would renew a lease that another instance now holds, and both would run the work. [Certain] that this happens as the code stands once restarts exist.

Fix: the heartbeat and every write by the worker (`status`, `ask`, `finish`) check that the stored `workerId` is this instance **and** `attempt` is the one this worker started with. If not, the worker is cancelled (`worker.cancelled = true`) and writes nothing. The compare-and-set on `version` already makes each write atomic; the added check makes a stale worker harmless to the record. It cannot stop the stale worker's side effects outside the record, which is why (a) puts idempotency on the author.

## Tests

- Unit (`TaskService`, in-memory store, fake clock): claim after lease expiry; only one of two services wins; attempt limit ends `failed`; non-restartable is still `failed`; `cancelRequested` wins over restart; a stale worker is cancelled and does not overwrite the new attempt; `close()` hands a restartable task over.
- Store: `JdbcTaskStoreTest` round-trips the three new fields; the cluster IT on PostgreSQL, MariaDB and MySQL.
- Cluster IT: `TasksClusterBase` kills A mid-task with a restartable demo task and expects B to finish it with `attempt` 2. The fixture's `task_demo` needs a `restartable` mode.

## Risks

- **Arguments at rest.** Documented with the SPI; the TTL bounds it. Not encrypted by the library.
- **Stored caller outlives the token.** Documented; restart can be switched off globally with `mcp.tasks.max-attempts=1`.
- **Schema change for existing 0.7.0 users of the JDBC store.** Three `ALTER TABLE` statements; listed in the changelog.
- **Double side effects.** Inherent to at-least-once; the author's job, said in the Javadoc of `restartable()`.

## Not in this note

External job handles (`TaskHandle`): a different model, where the job lives in another system and the task only mirrors it. That gives real durable execution; this note gives only "run it again".

