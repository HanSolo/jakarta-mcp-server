# mcp-example-tasks

A WAR with one tool, `build_report`, that runs as a task. It shows what the Tasks extension (`io.modelcontextprotocol/tasks`) looks like from both sides.

What the tool does (`ReportTool.java`): it works through the report section by section (a second each) and sets a status message for every section; when it is done it asks the client whether to publish (the task is `input_required` until the client answers with `tasks/update`); it stops when the task is cancelled. A client that did not declare the extension waits for the same work on the request and gets the plain result, without the question.

## Run it

```bash
mvn -pl mcp-example-tasks -am package -DskipTests
docker run --rm -p 8080:8080 -e MCP_TASKS_ENABLED=true \
  -v "$PWD/mcp-example-tasks/target/mcp-example-tasks.war:/opt/payara/deployments/ROOT.war" payara/micro:latest
```

The extension is off unless `MCP_TASKS_ENABLED=true` (or `-Dmcp.tasks.enabled=true`). Tasks are kept in memory here, so the server logs a warning at startup; see the main README for the JDBC store.

## Try it

`./demo.sh` starts the task, polls `tasks/get` once a second, answers the question and prints the result:

```
== tools/call build_report (2 sections)
{"jsonrpc":"2.0","id":1,"result":{"resultType":"task","taskId":"...","status":"working",...}}
== polling tasks/get
status=working section 1 of 2
status=working section 2 of 2
status=input_required
== tasks/update: publish = true
status=completed
{... "result":{"content":[{"type":"text","text":"Report on 'quarterly sales' with 2 sections, published"}]} ...}
```

`./demo.sh listen` does the same without polling: it opens `subscriptions/listen` with `notifications.taskIds` and prints each `notifications/tasks` message the server pushes (the output above is illustrative, not a recorded run).

By hand, the two requests that matter (headers as in the main README; `Mcp-Name` is the task id for `tasks/*`):

```bash
# start: the client declares the extension in _meta
curl -s localhost:8080/mcp -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -H 'MCP-Protocol-Version: 2026-07-28' -H 'Mcp-Method: tools/call' -H 'Mcp-Name: build_report' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"build_report","arguments":{"topic":"x"},"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{"extensions":{"io.modelcontextprotocol/tasks":{}}}}}}'
```

Leave `extensions` out of `clientCapabilities` and the same call returns the finished result after the full wait, without a task.

Cancel with `tasks/cancel` (`{"taskId": ...}`): the work notices at the next section and the task ends as `cancelled`.
