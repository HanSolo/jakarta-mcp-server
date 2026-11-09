# Changelog

## Unreleased

### Added
- **Notification bridge:** `McpNotificationBridge` passes every change announced to `McpNotifications` on to the other server instances, and `McpNotifications.receive(McpChange)` tells the clients of the receiving instance. `McpChange` encodes a change as a short JSON text. A separate thread hands changes to the bridge, so announcing a change still never blocks; the queue holds 1000 changes. See "Several instances" in the README.
- **`scripts/it.sh`:** runs the integration tests on one or more servers, prepares Docker for Testcontainers, and fails when test classes were skipped instead of reporting `BUILD SUCCESS`.

- **Integration tests** for dynamic registration, argument validation over HTTP, the notification bridge between two instances and token introspection against Keycloak, with the new test-only `mcp-it-fixture` module. Passed on Payara, WildFly and Open Liberty (2026-11-09).

### Changed
- `McpNotifications` has a second constructor, `McpNotifications(McpConfig, Instance<McpNotificationBridge>)`, which CDI uses; `McpNotifications(McpConfig)` still works and has no bridge.
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
