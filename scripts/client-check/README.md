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

A second client that was not written for this project. Checked by hand with MCP Inspector 2.14.0 on 2026-11-10.

**Start the server** (from the repository root; Docker is needed):

```bash
# Tasks
mvn -pl mcp-example-tasks -am package -DskipTests
docker run --rm -p 8080:8080 -e MCP_TASKS_ENABLED=true \
  -v "$PWD/mcp-example-tasks/target/mcp-example-tasks.war:/opt/payara/deployments/ROOT.war" payara/micro:latest

# MCP Apps (stop the container above first, both use port 8080)
mvn -pl mcp-example-apps -am package -DskipTests
docker run --rm -p 8080:8080 \
  -v "$PWD/mcp-example-apps/target/mcp-example-apps.war:/opt/payara/deployments/ROOT.war" payara/micro:latest
```

**Start the Inspector** (Node 20 or newer; version 2.x, shown in the lower left corner):

```bash
npx @modelcontextprotocol/inspector@latest --transport http --server-url http://localhost:8080/mcp --protocol-era modern
```

`--protocol-era modern` is required. The Inspector's default era is `legacy`: it opens with the 2025 `initialize` handshake, and this server, which speaks only 2026-07-28, answers `-32020 Missing MCP-Protocol-Version header`. That is the correct answer to a legacy client, not a server fault.

**Run the sequence** and compare with the script's output:

1. Connect. The Inspector reports protocol version `2026-07-28`.
2. List tools: `build_report` is there.
3. Call `build_report` with `topic` and `sections` 2. This Inspector declares the Tasks extension, so a task comes back. A client that does not declare it waits about two seconds and gets the text inline; that is also correct.
4. Watch the task: `working` with a status message per section, then `input_required` with the publish question.
5. Answer it (`publish` = true). The task becomes `completed` and the result says "published".
6. Start another task with 8 sections and cancel it: it ends as `cancelled`.
7. Apps: stop the Tasks server, start the Apps example, connect again and call `show_clock`. The Inspector shows the page `ui://clock/app.html` with the time; the refresh button calls `refresh_clock` through the host.

Write down every difference between the script and the client. A difference that is in the client's favour is a finding about the server or the spec.

**What this found:** the clock page sent `clientInfo` in `ui/initialize`; the MCP Apps handshake between a page and its host needs `appInfo` (`clientInfo` belongs to the MCP `initialize` between client and server, and a misleading example in the 2026-01-26 specification uses it for the page, see [ext-apps issue 634](https://github.com/modelcontextprotocol/ext-apps/issues/634)). Fixed; `AppsIT` now checks for it.

## OAuth: browser login with MCP Inspector's CLI

Checked by hand with MCP Inspector 2.14.0 on 2026-11-10: the authorization-code flow with PKCE against this server and Keycloak.

**Start the stack** (from the repository root; Docker is needed). After changing `keycloak/mcp-realm.json`, run `down` before `up`: the realm is only imported when it does not exist yet.

```bash
mvn -DskipTests package
docker compose -f keycloak/docker-compose.yml up
```

**Log in and list the tools.** Stop any web Inspector first (it holds port 6274, which the callback below uses). A browser window opens; sign in as `alice` / `alice`.

```bash
npx @modelcontextprotocol/inspector@latest --cli --transport http --server-url http://localhost:8080/mcp --protocol-era modern \
  --client-id mcp-inspector --callback-url http://127.0.0.1:6274/oauth/callback --method tools/list
```

**Call a tool with the stored token** (no second login):

```bash
npx @modelcontextprotocol/inspector@latest --cli --transport http --server-url http://localhost:8080/mcp --protocol-era modern \
  --use-stored-auth --method tools/call --tool-name add --tool-arg a=2 --tool-arg b=3
```

Expected: `structuredContent` `{"sum": 5}` and `isError` false. `--relogin` discards the stored grant.

**Why a pre-registered client:** the Inspector registers itself dynamically unless it is given a client ID, and Keycloak's default policies refuse anonymous registration (`Trusted Hosts`, then `Allowed Client Scopes`). Dynamic client registration is deprecated in 2026-07-28 in favour of Client ID Metadata Documents, so the realm defines the public client `mcp-inspector` (authorization code with PKCE, redirect URIs on port 6274, default scopes `mcp-audience` and `mcp:tools`, optional `offline_access`).

**What this found, all in the example realm and none in the library:**
- `--client-id` exists only in the Inspector's CLI and TUI; the web client takes the client through its settings dialogs (not tried).
- The Inspector asks for `offline_access` next to `mcp:tools`, so the client must allow it.
- A user imported from a realm file has no roles unless the file lists them; without the default roles Keycloak answers `Offline tokens not allowed for the user or client`. `alice` now has `default-roles-mcp`.
