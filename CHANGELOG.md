# Changelog

## Unreleased

### Added
- **Dynamic registration:** `McpRegistrations` adds, replaces and removes tools and prompts at runtime and sends `list_changed` to subscribed clients. See "Dynamic registration" in the README.

### Changed
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
