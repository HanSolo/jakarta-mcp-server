# Changelog


## Unreleased

### Added
- **`InterceptorsIT` and fixture classes (`Audited`, `AuditInterceptor`, `AuditedTool`, `AuditedHandlerTool`, `CallLog`, `GET`/`DELETE /admin/calls`):** show that audit, a policy gate and a rate limit can be built around tools with a plain CDI interceptor, for `@McpTool` methods and for `McpToolHandler` beans, without a hook from the library. Passed on Payara, WildFly and Open Liberty in CI.
- **Design note `docs/design/api-review-1.0.0.md`:** which classes 1.0.0 freezes, which are internal or provisional, and why.
- **README:** a section "Adding the library to your own application" (dependency, `-parameters`, registering the endpoint with `getClasses()`, what a failed tool declaration does, `tools/list` with curl); a note on `java.time` types and `List` returns; the confirmation form and the sensitive-text pitfall under "Elicitation"; "Audit, policy gates and rate limits" under "Writing tools"; "Rate limiting" under "Non-goals" (a gateway or a JAX-RS filter does it, not the library).

### Changed
- `ToolScanner.scan`, `PromptScanner.scan`, `CompletionScanner.scan` and `ResourceScanner.scan` return the interfaces (`List<McpToolHandler>`, `List<McpPromptHandler>`, `List<McpCompletionHandler>`, and `Found(List<McpResourceHandler>, List<McpResourceTemplateHandler>)`) instead of the `Annotated*Handler` classes. Code that only uses the interface methods compiles unchanged; a variable declared with the old class type needs the interface.
- Javadoc: the `Annotated*Handler` classes, `CdiAnnotationScanner`, `SchemaGenerator`, `JsonConverter`, `JsonMapper`, `ResultMapper`, `BearerJwtAuthenticator`, `JwtVerifier`, `JwksKeyResolver`, `IntrospectionVerifier`, `ListenExecutor` and `McpHttpResponse` are marked internal; `TaskStore` and `TaskRecord` provisional; the sealed result types document that a variant can be added. No code change.
- README "Stable API and internals" lists what 1.0.0 freezes per package, the provisional SPI, the sealed types, the rule for adding record components, and what the protocol promise is (one specification version per release line).
- CI: `actions/checkout` 5, `actions/setup-node` 5 and `actions/upload-artifact` 6, which run on Node 24 (the runner warned that the old versions declare Node 20).


## 0.9.0 (2026-11-11)

### Added
- **Recursive records in `@McpTool` signatures:** a record that refers to itself, directly or through other records, collections, maps or `Optional` (`record Folder(String name, List<Folder> folders)`), gets `$defs` at the root of the tool's `inputSchema` and `outputSchema` and a `$ref` where it is used. Before, the tool failed at startup with "recursive record types cannot be described without $ref". Records without recursion stay inline. `SchemaGenerator.schemaFor(Type, Defs)` and `SchemaGenerator.Defs` let code that builds a larger schema collect the definitions.
- **Argument validation: `unevaluatedProperties`, `unevaluatedItems` and `dependentSchemas`** are checked, counting what `properties`, `patternProperties`, `additionalProperties`, `prefixItems`, `items`, `contains` and the schemas applied in place (`$ref`, `allOf`, the matching `anyOf`/`oneOf` branches, `if`/`then`/`else`, `dependentSchemas`) have evaluated.
- **Argument validation: `format`** can be checked with `mcp.validation.formats=true` (default false, as `format` is an annotation in JSON Schema 2020-12): `date-time`, `date`, `time`, `duration`, `email`, `hostname`, `ipv4`, `ipv6`, `uri`, `uri-reference`, `uuid`, `json-pointer`, `regex`. Other format names are accepted.
- **Tests for the 0.8.0 paths that had none:** `JdbcTaskStoreTest` creates a 0.7.0 table, checks the startup refusal, applies the documented `ALTER TABLE` statements and uses the table; `TasksClusterBase` stops a third instance gracefully (with a lease of a minute) and expects another instance to finish its restartable task.
- **Dynamic registration of resources, resource templates and completions** through `McpRegistrations` (`addResource`/`replaceResource`/`removeResource`, `addResourceTemplate`/..., `addCompletion`/`replaceCompletion`/`removeCompletion`). Resources and templates send `notifications/resources/list_changed`; the `resources` and `completions` capabilities are switched on by the first registration. Handlers are checked as at startup; a resource that a tool points at with `_meta.ui.resourceUri` cannot be removed; removing a prompt or template drops its completions.
- **Dynamic skills (`"resources": "dynamic"`):** `McpSkill.dynamic(name, supplier)`, or `dynamic()`, `file(path)` and `paths()` on your own `McpSkill`, for skills whose content is generated per request. The entry in `skills/list` and `skills/get` carries `"resources": "dynamic"` instead of a manifest; frontmatter, files and directory listings are produced for every request, after the scope check. `SkillSet.Located` now reads its file lazily (`read()`).
- **New module `mcp-jakarta-bridge-jms`:** `JmsNotificationBridge`, a ready-made `McpNotificationBridge` on a Jakarta Messaging topic. Set `mcp.bridge.jms.connection-factory` (JNDI name); topic, credentials, time to live and reconnect pause are optional settings. Non-durable subscription per instance, a listener thread that polls with `receive` (works on container-managed connection factories) and reconnects, malformed messages ignored. Without the setting it does nothing.
- **External job handles:** a task can follow a job in another system without a worker thread. The tool returns `ToolResult.externalTask(handle)` (or `externalTask(taskId -> handle)` to give the other system the task id first); an `ExternalJob` bean per tool answers `poll(taskId, handle)` with `running`, `completed` or `failed`, and `cancel`; the server asks every `mcp.tasks.external-poll-ms` on whichever instance claims the poll. `McpExternalJobs.complete/fail/progress` lets the other system end the task at once through the application's own, authenticated endpoint. New settings `mcp.tasks.external-poll-ms` (5000) and `mcp.tasks.external-max-failures` (5).

### Changed
- `Elicitation.form(...)` accepts a form without properties, which asks for a plain accept/decline confirmation. Before, `schema()` and `toJson()` threw "A form needs at least one property", so a confirmation had to be built as raw JSON or with a dummy checkbox.
- A `double` without a fractional part is written as `5`, not `5.0`, in structured results; a `float` is written with the digits it prints with (`0.1`, not `0.10000000149011612`).
- The startup error for a task table without the columns of 0.8.0 names them and points to the `ALTER TABLE` statements.
- **Breaking for custom `TaskStore` implementations:** the interface has a new method, `pollDue(Instant now, int limit)`, and `TaskRecord` has three new components, `handle`, `nextPollAt` and `pollFailures` (before `version`). A store must keep the three fields and return the non-terminal tasks whose `nextPollAt` is set and not after `now`.
- **`JdbcTaskStore` needs three more columns and an index** (`handle_json`, `next_poll_at`, `poll_failures`, index on `next_poll_at`). `create-schema` does not add them to an existing table; the statements are in the class documentation. A table from 0.8.0 needs only these four statements, one from 0.7.0 needs all seven. The startup error says so, also when `create-schema=true` meets an old table.
- `ToolResult.text`, `error` and `structured(JsonObject)` return `ToolResult.Complete` instead of `ToolResult` (source compatible).
- Javadoc marks `McpRequestHandler`, `McpRegistry`, `McpStartup`, `TaskService`, `McpExtensions`, `UriTemplate`, `McpConfig` and `TaskConfig` as internal; README gains a "Stable API and internals" section.
- `scripts/release-check.sh`, the comment in the root `pom.xml` and the "Releasing to Maven Central" section name the fourth module, `mcp-jakarta-bridge-jms`, in the upload command (`-pl .,mcp-jakarta,mcp-jakarta-tasks-jdbc,mcp-jakarta-bridge-jms`).
- README: a "Non-goals" section (logging, sampling and roots, SSE and progress for `resources/read` and `prompts/get`, remote `$ref`, 2025-11-25) and a shorter "Not implemented yet".

### Removed
- The 18-argument `TaskRecord` constructor (a record that is not restartable, on its first attempt). Use the canonical constructor, which has `attempt`, `restartable` and `call` before `version` (pass `1, false, null, null, null, 0` for the old behavior; `handle`, `nextPollAt` and `pollFailures` are new, see above). Only custom `TaskStore` implementations that built records this way are affected; `TaskRecord.create(...)` is unchanged.

### Fixed
- `RestartableTasksTest`: the tests started another instance before the first worker had counted its run, so the worker of the take-over could count as the first run and block; they now wait for the first run to start (test only).
- The tickets example's `close_ticket` binds the user's confirmation to the ticket number and resolution it was asked about, with a signed `requestState` for the tool and caller. Before, any answer was accepted for any ticket number on the retry. The example's `TicketTools` constructor takes a `McpRequestStates` as third argument.
- A server instance that stops while it runs tasks now ends those tasks as `failed` (non-restartable) or hands them over (restartable) reliably. Before, the task could be written as `cancelled` because the stopping worker raced with `close()`, and a heartbeat that was running during shutdown could extend the lease of a task that had just been handed over.
- A hand-over that cannot be written (the store is unreachable while the server stops) leaves the task `working` so that its lease runs out and another instance takes it over; before, the worker wrote `cancelled`.
- `JdbcTaskStore` with `mcp.tasks.jdbc.url` keeps up to four idle connections and reuses them. Servers that delete the application's libraries before they destroy its beans (Payara Micro) made drivers that read their own jar on every connect (MariaDB) fail in `TaskService.close()`.


## 0.8.0 (2026-11-11)

### Added
- **MySQL verified:** `TasksClusterMySqlIT` runs the two-instance cluster scenarios on MySQL 8.4 with MySQL Connector/J (`mysql-connector-j`, in the test fixture only), in addition to PostgreSQL and MariaDB. The `LONGTEXT` columns and inline indexes from 0.7.0 work unchanged. Over a connection without TLS, MySQL 8's `caching_sha2_password` needs `allowPublicKeyRetrieval=true` in the JDBC URL (the IT sets it; in production, use TLS instead).
- **Build requirements are checked:** `maven-enforcer-plugin` (3.6.1) fails the build at the start with a readable message unless Maven is 3.9.0 or newer and the JDK is 21 or newer. 3.9.0 is the version CI and the maintainer use, not a proven lower bound.
- **Design note `docs/design/deprecated-features.md`:** why the library has no logging, sampling or roots API (2026-07-28 deprecates all three, and dynamic client registration), what it still owes (never send `notifications/message`), and when to revisit.
- **`McpStreamingTest.noLogMessagesAreSentWhetherOrNotALogLevelIsRequested`:** a streamed `tools/call` with and without `io.modelcontextprotocol/logLevel` never produces `notifications/message`.
- **Keycloak example realm:** new public client `mcp-inspector` (authorization code with PKCE, optional scope `offline_access`) so MCP Inspector can complete a browser login, and `alice` now has the realm's default roles (without them Keycloak refuses offline tokens). Verified with MCP Inspector 2.14.0's CLI: login, `tools/list` and `tools/call` against the example server. Steps in `scripts/client-check/README.md`. Dynamic registration against Keycloak still fails under its default policies (`Trusted Hosts`, `Allowed Client Scopes`); it is deprecated in 2026-07-28.
- **Restartable tasks:** `ToolResult.task(work).withRestartable()` lets another instance run the tool call again from the start when the instance running it is lost or stops. Without it nothing changes: a lost task is still `failed`. It is at-least-once, so the work must be safe to repeat; `TaskContext.attempt()` returns the run number. At most `mcp.tasks.max-attempts` runs (default 3, 1 switches restarts off), then the task fails as before. The record stores the call (arguments, caller, client capabilities) for such tasks only, and the restarted call runs with the original caller's scopes without checking the access token again. Open input requests and their answers are dropped on a restart, and the work asks again. A graceful shutdown hands a restartable task over at once instead of failing it. A worker whose task another instance has taken over is fenced off: it can no longer renew the lease or write to the record. The restarted tool call runs outside a request with a request scope started by the library (`RequestContextController`), so request-scoped beans keep working; the container's security context is not restored, use `ToolCall.caller()`. Design: `docs/design/restartable-tasks.md`.

### Changed
- The comment in the root `pom.xml` for the Maven Central upload now names all three modules of the command (`mvn -Prelease clean deploy -pl .,mcp-jakarta,mcp-jakarta-tasks-jdbc`).

### Upgrading the JDBC task table from 0.7.0
Restartable tasks add three columns, which `mcp.tasks.jdbc.create-schema` does not add to an existing table. Run once, with your table name (`mcp_tasks` by default); use `LONGTEXT` instead of `TEXT` on MariaDB and MySQL, `CLOB` on H2:
```sql
ALTER TABLE mcp_tasks ADD COLUMN attempt_no INT NOT NULL DEFAULT 1;
ALTER TABLE mcp_tasks ADD COLUMN restartable BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE mcp_tasks ADD COLUMN call_json TEXT;
```

### Notes
- **Conformance:** the `tasks-status-notifications` scenario is skipped by the conformance suite itself (it still targets the old POST-SSE channel and waits for a rewrite against `subscriptions/listen`), so it reports 0 checks for every server. `notifications/tasks` is covered by this project's own integration tests.

## 0.7.0 (2026-11-10)

### Added
- **`scripts/release-check.sh` and a CI job `release-check`:** build the publishable modules with the release profile (sources and Javadoc jars, no signing, no upload), so a Javadoc error fails in CI instead of at the Central upload.
- **`scripts/client-check/`:** a dependency-free Node client (`check.mjs`) that runs the Tasks flow (create, poll, `input_required`, `tasks/update`, `tasks/cancel`, errors, inline fallback, `subscriptions/listen`) or the MCP Apps checks (`--apps`) against a running example, plus a checklist for MCP Inspector and other clients.

### Changed
- **MariaDB and MySQL in `JdbcTaskStore`:** `create-schema` now uses `LONGTEXT` columns and declares the indexes inside `CREATE TABLE` when the database is MariaDB or MySQL (before, it used `CLOB` and `CREATE INDEX IF NOT EXISTS`, which they do not accept). New `JdbcTaskStore.ddl(table, textType, inlineIndexes)`. `TasksClusterIT` now has a MariaDB variant (`TasksClusterMariaDbIT`, shared code in `TasksClusterBase`). Verified on MariaDB only once that IT has run; MySQL itself is not tested.
- **`TaskStore.findAll(ids)`** (new, with a default that calls `find` for each id): a `subscriptions/listen` stream that watches several tasks now makes one store lookup per round instead of one per task. `JdbcTaskStore` overrides it with one `WHERE id IN (...)` query (chunks of 200 ids). Custom stores keep working unchanged.
- Dependency and plugin updates: parsson 1.1.9, PostgreSQL driver 42.7.14, Testcontainers 2.0.5, compiler 3.16.0, surefire/failsafe 3.6.1, resources 3.5.0, jar 3.5.1, war 3.5.1, source 3.4.0, javadoc 3.12.0, gpg 3.2.8, central-publishing 0.12.0. Not taken yet: JUnit 6 (major), H2 2.5, slf4j-simple 2.1 alpha.

### Fixed
- `ServerFixture.awaitDeployed` (integration tests) accepted WildFly's HTML welcome page (HTTP 200) as "deployed", so the first test class could start before the WAR was up and fail with HTML instead of JSON. It now needs a JSON body.
- Javadoc of `McpRequestHandler.handle` named a parameter `headers` after it was renamed `rawHeaders`; the 0.6.0 release build failed on it (the fix is in the 0.6.0 tag).

## 0.6.0 (2026-11-09)

### Added
- **Tasks extension (`io.modelcontextprotocol/tasks`, experimental, off by default):** `ToolResult.task(TaskWork)` with `TaskContext` (`status`, `ask`, `isCancelled`), `tasks/get`, `tasks/update` and `tasks/cancel`, a `TaskStore` SPI with the default `InMemoryTaskStore`, ownership checks on every request, leases with heartbeat and a sweeper that declares tasks without a live worker failed, TTL expiry, result size and concurrency limits, inline fallback for clients that did not declare the extension. Settings `mcp.tasks.*`. `TasksIT` added. See "Tasks" in the README.
- **`mcp-jakarta-tasks-jdbc`:** `JdbcTaskStore`, a `TaskStore` in a relational database (JNDI `DataSource` or JDBC URL), so several instances share tasks and they survive a restart. `TaskStore.available()` lets a store module sit unused in an application. Tests: `JdbcTaskStoreTest`, `TwoInstancesTest` (H2) and `TasksClusterIT` (two servers, PostgreSQL).
- **`notifications/tasks`:** `subscriptions/listen` accepts `notifications.taskIds`; the server acknowledges the ids it honors and pushes the full task state on every change, also for tasks that run on another instance (the stream looks in the task store every `mcp.tasks.notify-poll-ms`). A client that asks without declaring the extension gets -32021. Tests: `McpTaskNotificationsTest`, `TasksIT`, `TasksClusterIT`.
- **`mcp-example-tasks`:** a runnable example WAR: `build_report` runs as a task (status per section, a question to the client, cancellation) and falls back to the plain result for clients without the extension; `demo.sh` drives it with curl, polling or through `subscriptions/listen`.
- **`TasksJndiIT`:** the JDBC task store through a container-managed `DataSource` (`mcp.tasks.datasource=jdbc/mcp`) on Payara Micro with PostgreSQL: a connection pool and resource from a pre-boot command file, the driver on the server's library path; checks that the task row is in the database. Skipped on WildFly and Open Liberty (not scripted there).
- **Conformance:** the nine `tasks-*` scenarios pass on Payara (run with `MCP_TASKS_ENABLED=true`); `expected-failures.yml` is empty, the fixture WAR has the Tasks tools, and `scripts/it.sh` and CI start the conformance container with tasks on.
- **`McpToolHandler.taskSupport()`** (`TaskSupport.FORBIDDEN/OPTIONAL/REQUIRED`): announced as `execution.taskSupport` in `tools/list`. `REQUIRED` makes a call from a client without the Tasks extension (or with tasks off) fail with -32021 instead of running inline.
- **Fixed: the README's Tasks snippet used `Elicitation.form(...).bool(name)`, which does not exist; it is `.bool(name, settings)`.**
- **Fixed: request headers keep no blanks around their values.** Open Liberty passes a trailing blank on to the application (`Mcp-Name: "  name  "` arrived as `name  `), so the conformance check `sep-2243-server-accepts-whitespace-header-value` failed there (Payara and WildFly trim both ends). The server now removes spaces and tabs around every header value it reads, as RFC 9110 5.5 requires.

## 0.5.0 (2026-11-09)

### Added
- **Notification bridge:** `McpNotificationBridge` passes every change announced to `McpNotifications` on to the other server instances, and `McpNotifications.receive(McpChange)` tells the clients of the receiving instance. `McpChange` encodes a change as a short JSON text. A separate thread hands changes to the bridge, so announcing a change still never blocks; the queue holds 1000 changes. See "Several instances" in the README.
- **`scripts/it.sh --conformance` / `--only-conformance`:** runs the MCP conformance suite against the fixture WAR on each selected server and adds the result to the summary.
- **`scripts/it.sh`:** runs the integration tests on one or more servers, prepares Docker for Testcontainers, and fails when test classes were skipped instead of reporting `BUILD SUCCESS`.

- **Integration tests** for dynamic registration, argument validation over HTTP, the notification bridge between two instances and token introspection against Keycloak, with the new test-only `mcp-it-fixture` module. Passed on Payara, WildFly and Open Liberty (2026-11-09).

- **Icons and `_meta`:** `icons()` and `meta()` on `McpToolHandler`, `McpPromptHandler`, `McpResourceHandler` and `McpResourceTemplateHandler`, and `icons`/`meta` attributes on `@McpTool`, `@McpPrompt` and `@McpResource`. `Icon` and `Meta` (key validation) are new. Invalid icons or `_meta` keys fail at startup.
- **Rich content:** `Content` builds text, image, audio, `resource_link` and embedded-resource blocks with annotations; `ToolResult.of(...)`, `ToolResult.structured(data, blocks...)` and `ToolResult.Complete.withMeta(...)`; `ResourceContent.withMeta(...)` and `ResourceContent.toJson()`.

- **Extensions:** `McpExtension` beans are announced in `server/discover` (`capabilities.extensions`); `ToolCall`, `ResourceCall` and `PromptCall` get `clientExtension(id)` and `requireClientExtension(id)` (error -32021 when missing). `McpExtensions` is the registry; `Extensions` has the id check. `ExtensionsIT` added (passes on all three servers).

- **MCP Apps:** `Apps` (tool binding, resource meta builder with validated CSP origins, `Apps.html`, `Apps.clientSupports`), `McpAppsExtension`, `mcp.apps.enabled`, `ToolCall.clientSupportsApps()`. A tool whose `_meta.ui.resourceUri` names a missing resource now fails at startup. New example module `mcp-example-apps` (a page that speaks the host `postMessage` protocol, shows the tool result and refreshes through the app-only tool `refresh_clock`) and `AppsIT` (5 tests, passes on all three servers).

- **Trace context:** `TraceContext` (`traceparent`, `tracestate`, `baggage` from the request's `_meta`, validated, with `child()` and `toJson()` for forwarding) and `trace()` on `ToolCall`, `PromptCall`, `ResourceCall` and `CompletionCall`. The call records gained a `trace` component; the old constructors still exist.

- **Elicitation:** `Elicitation` (form builder for the specification's schema subset, URL requests, `answer`, `Form.problems` to check an answer, `supports` for the declared modes), `InputRequests`, `ToolResult.ask`, `PromptResult.ask`, `ResourceResult.ask`, and `clientSupportsElicitation(mode)` on the call records. Form mode refuses property names and texts that look like sensitive data. The conformance fixtures use it. `ElicitationIT` added (passes on all three servers).

- **Keep-alive on streamed `tools/call`:** while a streamed call runs, the server writes an SSE comment line whenever the stream has been quiet for `mcp.listen.keepalive-ms`. A failed write cancels the call even if the tool reports no progress; `ToolCall.isCancelled()` and `ProgressReporter.isCancelled()` tell the tool. `KeepAliveIT` added (passes on all three servers).

- **Parameter headers:** the `x-mcp-header` schema annotation and `Mcp-Param-*` request headers. The server checks the headers against the arguments on `tools/call` (HTTP 400, `-32020`) and refuses invalid annotations at registration; `@McpParam(header = ...)` emits the annotation. New fixture tool `routed_echo` and `ParamHeadersIT` (7 tests, pass on all three servers); the conformance fixture has `test_custom_header`.

- **Skills (experimental):** `McpSkill` (with `of` and `fromClasspath`), announced as `io.modelcontextprotocol/skills`; `skills/list`, `skills/get`, skill files through `resources/read`, and `resources/directory/read`. Manifests with SHA-256 digests are computed at startup and invalid skills fail the deployment. `SkillsIT` added (passes on all three servers).

### Fixed
- Base64 header values (`=?base64?...?=`, for `Mcp-Name` and `Mcp-Param-*`) without padding were accepted because the JDK decoder does not require it; they are now rejected with `HeaderMismatch`, as the specification's test-case table requires (found by the conformance suite).
- `McpAppsExtension` is now `@Vetoed`. The library archive uses bean discovery mode `all`, so the container registered the base class as a bean as well as any subclass, and the server failed with "Duplicate MCP extension 'io.modelcontextprotocol/ui'" at the first request (found by `AppsIT` on Payara).

### Changed
- `McpRequestHandler` has a new constructor with a sixth parameter, `McpExtensions`, which CDI uses; the old constructors still work and announce no extensions.
- `ToolResult.Complete` and `ResourceContent` gained a `meta` component; the old constructors still exist. Code that deconstructs these records with a record pattern needs the extra component.
- The `_meta` of a tool result now keeps what the tool put there; the server's `serverInfo` key is added on top.
- `McpNotifications` has a second constructor, `McpNotifications(McpConfig, Instance<McpNotificationBridge>)`, which CDI uses; `McpNotifications(McpConfig)` still works and has no bridge.
- `scripts/it.sh` deletes old Failsafe reports before each run, so a summary can no longer show results of an earlier run.
- `McpRegistrations` tells the clients of its own instance only and does not publish to a bridge: the registry is per instance, so make the same registration call on every instance.

## 0.4.0

### Added
- **Argument validation:** tool arguments are now checked against the whole `inputSchema` (nested objects, arrays, ranges, patterns, `enum`, `oneOf`, `if`/`then`/`else`, local `$ref`, ...), not only the top level. See "Argument validation" in the README.
- **Opaque tokens:** `mcp.auth.introspection-uri` switches authentication from local JWT verification to RFC 7662 token introspection (`IntrospectionVerifier`), with client credentials, a short cache, audience binding and a 503 when the endpoint is unavailable. `mcp.auth.jwks-uri` is no longer required in that mode. See "Opaque tokens" in the README.
- **Dynamic registration:** `McpRegistrations` adds, replaces and removes tools and prompts at runtime and sends `list_changed` to subscribed clients. See "Dynamic registration" in the README.

### Changed
- **Behaviour change:** a hand-written tool schema that is stricter than the tool's code used to be advertised but not enforced; calls it forbids are now rejected with -32602 before the tool runs.
- `AuthConfig` has six more constructor arguments (the old constructor still works); `JwtVerifier` implements the new `TokenVerifier` interface.
- The registry's tool and prompt maps are concurrent. `McpRegistry.hasPrompts()` is new; the `prompts` capability stays on once a prompt was registered at runtime.

## 0.3.0

Everything below is new since 0.1.0 (0.2.0 was never published).

### Added
- **Streaming:** `tools/call` with a `progressToken` from a client that accepts `text/event-stream` is answered as Server-Sent Events: progress notifications, then the result. Closing the connection cancels the call. `ToolCall.progress(...)`, `wantsProgress()`.
- **Notifications:** `subscriptions/listen` streams with `notifications/tools|prompts|resources/list_changed` and `notifications/resources/updated`, driven by the new `McpNotifications` bean. Settings `mcp.listen.max`, `mcp.listen.max-per-caller`, `mcp.listen.keepalive-ms`.
- **Async listen mode** (`mcp.listen.async`, optional `mcp.listen.executor`): listen streams run on virtual threads instead of holding a container request thread.
- **Signed request state:** `McpRequestStates` signs (HMAC-SHA256), expires and binds the `requestState` of multi round-trip tools, so a state altered by the client is refused with -32602. Settings `mcp.request-state.secret`, `mcp.request-state.ttl-ms`. Set the same secret on every node of a cluster.
- **Completion** (`completion/complete`), **pagination** (`mcp.page-size`), resources and prompts, including `@McpResource`, `@McpPrompt`, `@McpComplete`.
- **`mcp-example-tickets`:** a realistic example with a database, scopes and a confirmation step.
- **`mcp-example-conformance`:** a fixture WAR for the official MCP conformance suite, and a CI job that runs it. All scored scenarios for 2026-07-28 pass; the Tasks extension and `x-mcp-header` scenarios fail because those features are not implemented.

### Changed
- **Origin check:** requests with an `Origin` header are now allowed from loopback origins (`localhost`, `127.0.0.1`, `[::1]`, any port) and from `mcp.allowed-origins`; everything else gets 403. Before, any `Origin` was refused unless listed. This fixes the DNS-rebinding scenario of the conformance suite without opening the endpoint to a rebinding attacker, whose origin is always its own domain.
- `McpConfig` has two more constructor arguments; the old constructors still work.

### Notes
- Compiles to Java 21 bytecode and runs on newer JDKs. On JDK 21 virtual threads pin their carrier in `synchronized` blocks; `Subscription` uses `ReentrantLock` for that reason.
- Verified on Payara, WildFly and Open Liberty (the tickets example is skipped on Liberty, which needs a default data source).
