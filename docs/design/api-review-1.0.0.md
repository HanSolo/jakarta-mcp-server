# API review for 1.0.0 (draft for decisions)

Basis: the tree from your zip of 2026-11-11 plus my batches 10 and 12, read by me, never compiled in the sandbox. Counts come from a script, not from a Javadoc run. Tags: [Certain] seen in the code, [Likely] inference, [Guessing] gap filling.

## 1. What the README promises today

Frozen at 1.0.0 (README "Stable API and internals"): `eu.hansolo.mcp.api`, seven classes of `eu.hansolo.mcp.server` (`McpRegistrations`, `McpNotifications`, `McpNotificationBridge`, `McpChange`, `McpExternalJobs`, `McpRequestStates`, `Settings`), and the packages `protocol`, `auth`, `rest`, `tasks`. Internal by Javadoc: eight `server` classes. Not mentioned either way: the whole `tools` package ("for the library's own startup and for tests") and `server.McpHttpResponse`.

[Certain] The promise is wider than what the documentation and examples ever show. Evidence from README and examples (mentions): `BearerJwtAuthenticator`, `JwtVerifier`, `JwksKeyResolver`, `IntrospectionVerifier`, `TokenVerifier`, `KeyResolver`, `AuthConfig`, `ListenExecutor`, `McpHttpResponse`, `SchemaGenerator`, `TaskRecord`: none, in either place.

## 2. Verdict per package

| Package | Proposal |
| --- | --- |
| `api` (44 types) | Freeze. Mechanical work only: Javadoc, evolution rule (section 4). |
| `protocol` | Freeze `McpProtocolException`, `ErrorCodes`, `MetaKeys`. `McpVersions` and `JsonRpc`: freeze the names, not the values (the spec changes them). |
| `auth` | Freeze the extension points: `McpAuthenticator`, `TokenVerifier`, `KeyResolver`, `TokenException`, `ProtectedResourceMetadata`. Mark `BearerJwtAuthenticator`, `JwtVerifier`, `JwksKeyResolver`, `IntrospectionVerifier` and the long constructors of `AuthConfig` internal. See D3. |
| `rest` | Freeze `McpResource` and `ProtectedResourceMetadataResource` (what an application registers). `ListenExecutor` internal. |
| `tasks` | `TaskStore` and `TaskRecord` are the problem. See D2. `TaskStatus`, `InMemoryTaskStore`: freeze. |
| `server` | The seven classes stay frozen. `McpHttpResponse` internal (used between the endpoint and the handler, never by an application). |
| `tools` | See D1. |

## 3. Decisions for you

**D1 `tools` package.** [Certain] An application uses it in exactly one way: `TicketsTest` calls `ToolScanner.scan(Class, BeanSource.of(bean))` to unit-test a tool bean without a container. That is worth keeping. Everything else in the package (`Annotated*Handler`, `CdiAnnotationScanner`, `SchemaGenerator`, `JsonConverter`, `JsonMapper`, `ResultMapper`) is an implementation detail.
*Recommendation:* freeze the four scanners' `hasX` and `scan` methods and `BeanSource` (document the testing use in the README), and mark the rest internal. Change `scan` to return `List<McpToolHandler>` (and the same for the others) so the `Annotated*Handler` classes need not be frozen. That return-type change is breaking, which is allowed before 1.0.0.
*Risk if you skip it:* freezing `AnnotatedToolHandler` and `SchemaGenerator` ties the schema generator's output and signatures to 1.x.

**D2 `TaskStore` and `TaskRecord`.** [Certain] `TaskRecord` is a public record with 24 components, and its constructor changed in both 0.8 and 0.9 (0.9 removed the 18-argument one). `TaskStore` gained an abstract method in 0.9 (`pollDue`). A custom store has to call the canonical constructor to rebuild a record, so every new component breaks it. [Likely] It will change again: tasks for anything but `tools/call` is still open.
*Options:* (a) keep both, but declare them a provisional SPI outside the 1.x promise: additions and constructor changes may come in a minor release, announced in the changelog, and store implementations are expected to follow. (b) redesign `TaskRecord` as a final class with a builder, so components can be added without breaking implementors. About six files change, among them both stores and their tests.
*Recommendation:* (a). The type changed in two releases in a row, so freezing it would be a promise we are likely to break, and few outside stores are likely to exist. (b) is the right design if you expect outside store implementors soon.

**D3 `auth`.** AuthConfig is configured through settings (`AuthConfig()`), and its two long constructors exist for tests. Concrete verifiers are not documented anywhere.
*Recommendation:* internal for the concrete classes and the long constructors; the settings and the `McpAuthenticator` interface are the contract. No builder (it would be a new feature).
*Risk:* if someone already builds a `JwtVerifier` by hand, they get a note in the changelog, not a break, because the classes stay public.

**D4 What 1.0.0 promises about the protocol.** [Likely] This is the real uncomfortable point: the API can be frozen, but the wire protocol cannot, because the specification is dated and will change. Today only 2026-07-28 is supported.
*Proposal for the README:* 1.x supports the 2026-07-28 specification. A later specification version is added in a minor release if the API can carry it, and in 2.0 if it cannot. Extensions (Tasks, Apps) follow their own specifications: an incompatible change in the extension may ship in a minor release, with a changelog entry. This avoids a promise nobody can keep.

**D5 Sealed types.** `ToolResult` gained `ExternalTask` in 0.9. Adding a variant breaks exhaustive `switch` code in applications. *Proposal:* say in the Javadoc and README that applications create these types and do not switch over them, and that variants can be added in a minor release.

## 4. Mechanical items (no decision needed)

1. Javadoc: [Likely] about 149 of about 376 public declarations in the frozen packages have none (script count; records' components and some nested types may be miscounted). Worst: `Content` 18, `AuthConfig` 16, `TaskRecord` 11, `Elicitation` 10, `McpProtocolException` 9, `ToolResult` 9, `Apps` 6.
2. Evolution rule for records: when a component is added, keep the old canonical constructor as an overload, as `ToolCall` did twice. Write it into the contributing notes and keep `Caller`, `ResourceContent` and the call records to it.
3. Add the "Internal" paragraph to everything D1 and D3 move, as in batch 8.
4. README "Stable API and internals": update the lists to the final decisions, and add the `McpHttpResponse` and `tools` entries.
5. A line in the README on semantic versioning for 1.x, and on settings keys (frozen) versus JDBC schema (migration notes in the changelog).

## 5. Open points I cannot decide

- Whether any outside code builds the concrete `auth` classes or a custom `TaskStore`. If you know of one, D2 and D3 change.
- Whether `Apps` and `McpAppsExtension` should be treated as provisional like Tasks, since the Apps extension is the youngest part.
