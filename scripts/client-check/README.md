# Client check

`check.mjs` is a small client written against the spec text, not against this project's code. It needs Node 20 or newer and no packages.

```bash
# Tasks: deploy mcp-example-tasks with MCP_TASKS_ENABLED=true, then
node scripts/client-check/check.mjs http://localhost:8080/mcp

# MCP Apps: deploy mcp-example-apps, then
node scripts/client-check/check.mjs http://localhost:8080/mcp --apps
```

Exit code 0 means every check passed, 1 means a check failed, 2 means the server could not be reached.

## What a FAIL means

A failure is a disagreement between this script and the server. It does not say who is right. For each one, look at the spec text first:

| Check | Where to look |
|---|---|
| discover lists the version / the extension | `server/discover` result, `capabilities.extensions` |
| `CreateTaskResult` has `taskId` and `status` | flat result of `tools/call` in the Tasks extension (SEP-2663) |
| `input_required` carries `inputRequests` | `tasks/get` result for a task waiting for the client |
| `tasks/update` with `inputResponses` | request shape for answering |
| `ttlMs` is reported | task lifetime field (name is the least certain one: the script accepts `ttlMs` or `ttl`) |
| `tasks/cancel` ends as `cancelled` | cancellation is cooperative, so the task may need a moment |
| -32602 unknown task, -32021 missing extension, -32020 header mismatch | error codes of the extension and the header rules |
| listen: acknowledgement, `notifications/tasks`, final notification with the result | `subscriptions/listen` with `notifications.taskIds` |

## With MCP Inspector (or any other client)

Run the same sequence by hand and compare with the script's output:

1. Connect to the URL. The client must send protocol version `2026-07-28`; if it cannot, that is a client limit, not a finding.
2. List tools: `build_report` is there.
3. Call `build_report` with `topic` and `sections` 2. With the Tasks extension declared, a task comes back; without it, the call runs about two seconds and returns the text inline.
4. Watch the task: `working` with a status message per section, then `input_required` with the publish question.
5. Answer it (`publish` = true). The task becomes `completed` and the result says "published".
6. Start another task with 8 sections and cancel it: it ends as `cancelled`.
7. Apps: call `show_clock`; a client with MCP Apps support shows the page `ui://clock/app.html`, any other client gets the text.

Write down every difference between the script and the client. A difference that is in the client's favour is a finding about the server or the spec.
