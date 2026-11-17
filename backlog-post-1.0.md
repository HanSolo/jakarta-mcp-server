# Backlog after 1.0.0: make the library feel native to Jakarta EE developers

Basis: the tree from your zip of 2026-11-13, read by me, never compiled. Tags: [Certain] seen in the code, [Likely] inference, [Guessing] gap filling. Trigger: a real user hit `LocalDateTime` in a tool result and got `IllegalStateException: Cannot convert java.time.LocalDateTime to JSON`.

Proposed home: `docs/design/backlog-post-1.0.md`.

## 0. Ground rules (the uncomfortable part)

1. [Certain] "Everything Jakarta EE users are used to" has no end. Without a rule, the backlog becomes JSON-B, JPA, Bean Validation, Security, Config and Telemetry all at once, and 1.x turns into a second framework. Proposed admission rule: an item goes in only if (a) it is part of a Jakarta EE or MicroProfile standard, (b) it appears in an ordinary DTO or service class, and (c) its mapping to JSON and to a JSON Schema is unambiguous. Fails the rule: Jackson annotations, Spring types, lazy JPA graphs.
2. [Likely] None of this belongs in 1.0.0. Every item below is additive (new types, new optional behaviour), so it fits a minor release under the evolution rules. I would use a different approach than squeezing `java.time` into 1.0.0, because 1.0.0 means freeze and real-host testing. The risk of waiting is that early users meet the error again; the mitigation is the README note and the cookbook (item 12), which cost nothing.
3. [Likely] Release budget: batch the P1 items into one 1.1.0 instead of one release per type. Each release costs several "releases" on Central.
4. Rule for behaviour: unsupported stays loud. Never fall back to `toString()`; the failure at startup or in the result is a feature.

## 1. What exists today (so the gaps are real)

- [Certain] Return mapping (`JsonMapper`, `ResultMapper`): strings, booleans, integers, `BigInteger`, `BigDecimal`, `float`/`double`, enums, `Optional`, `Map`, collections, arrays, records, JSON-P types. Everything else throws `IllegalStateException`. A record becomes `structuredContent` with an `outputSchema`; scalars become text; a `List` or `Map` becomes text containing JSON.
- [Certain] Parameter conversion (`JsonConverter`) and schema generation (`SchemaGenerator`): the same list, with records as the only structured type. Other classes fail with "no converter" / "supported are scalars, enums, List/Set/Collection/arrays, Map<String,V>, records, Optional and the JSON-P types".
- [Certain] Exceptions from a tool: `ToolExecutionException` becomes `ToolResult.error(message)`. Any other exception is rethrown by `AnnotatedToolHandler`; [Certain] the README says it is logged and replaced by a generic message, so agents cannot self-correct (the exact handler path for tools was not opened; prompts show the same pattern: `McpProtocolException.internal("Prompt failed")`).
- [Certain] `Settings` reads system properties and environment variables only.
- [Likely] Nothing in `mcp-jakarta` uses Bean Validation, JSON-B annotations, `SecurityContext` or MicroProfile APIs (my grep matched only the example apps, the REST classes and the task service; check each before starting).
- [Certain] CDI interceptors on tool beans work (`InterceptorsIT`); `@RolesAllowed` is not covered.

## 2. Backlog

Priority = onboarding pain divided by cost. Size: S under a day, M a few days, L a week or more.

### P1: first minor release (1.1.0)

| # | Item | Why | Design sketch | Size |
| --- | --- | --- | --- | --- |
| 1 | **`java.time` and common value types** in and out: `LocalDate`, `LocalTime`, `LocalDateTime`, `OffsetDateTime`, `ZonedDateTime`, `Instant`, `Duration`, `Period`, `Year`, `YearMonth`, plus `UUID`, `URI`, `URL`, `Locale`, `Currency`, `java.util.Date` and `Calendar` (as `Instant`) | The reported case. JSON-B users expect it. | ISO-8601 strings via the types' own `toString()`/`parse()`. Schema `{"type":"string","format":"date-time"}` (`date`, `time`, `duration`, `uuid`, `uri`). `LocalDateTime` has no zone: document that it is passed through untouched. Parse errors become -32602 with the path and the expected format. `SchemaValidator` already has a `formats` switch ([Certain], the `validate(..., boolean formats)` overload): check that it covers these formats. | M |
| 2 | **Plain JavaBeans and POJOs** as parameters and results (getters/setters, public fields), not only records | Jakarta DTOs and legacy services are rarely records. | Same rules as records, read via getters; setters or a no-arg constructor for input. Explicit exclusion: classes with cycles beyond the depth limit, and JPA proxies (fail with a clear message). Needs a decision, see D1. | L |
| 3 | **Exception mapping**: a `ToolExceptionMapper` extension point, plus defaults for `IllegalArgumentException`, `jakarta.ws.rs.WebApplicationException` (and its subclasses such as `NotFoundException`), `jakarta.validation.ConstraintViolationException` and `EJBException` causes | Today an ordinary exception loses its message; agents need the message to retry. | CDI bean(s) implementing the mapper, ordered by `@Priority`. Default: `ToolResult.error(message)` for a short allow-list; internal errors stay opaque (no stack traces, no secrets). | M |
| 4 | **Bean Validation** on parameters and record components: `@NotNull`, `@Size`, `@Min`, `@Max`, `@Pattern`, `@Email`, `@Positive` and so on | The most familiar way of describing constraints. | Two parts: (a) map to schema keywords (`minLength`, `maximum`, `pattern`, `format`) so the model sees them; (b) validate at call time with the container's `Validator` when it is present (optional dependency), and map violations to -32602 or a tool error. Keep (a) without (b) as the fallback. | M to L |
| 5 | **Cookbook README section "Coming from REST/JSON-B"**: types table, what to return, how to wrap a REST resource, LocalDateTime example | Zero code, immediate relief. | A table "type, accepted, schema, notes" generated from the code's tests so it stays true. | S |

### P2: next minor (1.2.0)

| # | Item | Why | Design sketch | Size |
| --- | --- | --- | --- | --- |
| 6 | **JSON-B annotations** honoured when JSON-B is on the classpath: `@JsonbProperty`, `@JsonbTransient`, `@JsonbDateFormat`, `@JsonbNillable`, `@JsonbCreator` | DTOs already carry them. | Read the annotations reflectively inside `JsonMapper`, `JsonConverter` and `SchemaGenerator` (no hard dependency). Do not delegate to a `Jsonb` instance: the schema must match the output, and two serializers drift apart. | M |
| 7 | **`jakarta.ws.rs.core.Response` as a return type** (status and entity) | The user's own scenario: tools that wrap REST endpoints. | Map 2xx entity to the normal result, other statuses to `ToolResult.error` with the entity text. | S |
| 8 | **MicroProfile Config** as an extra source in `Settings` (after system properties and environment, before defaults) | Expected in MP runtimes (Payara, Open Liberty, WildFly). | Optional lookup through `ConfigProvider` when present; keys stay the same. A frozen settings-key list means the order of sources must be documented. | S |
| 9 | **CDI events for tool calls** (`ToolCalled` before, `ToolCompleted` or `ToolFailed` after, with tool name, caller subject, duration, outcome; no arguments by default) | The colleague's audit-event idea, and an observer is more familiar than an interceptor. Also the hook for MicroProfile Telemetry/Metrics. | Fire from the handler path; the event types are new API, so they need the freeze review. | M |
| 10 | **Security bridging**: `@RolesAllowed` and `isUserInRole` mapped from token claims | Row 7 of the colleague's list; [Certain] not covered today. | The `Caller` already holds scopes and claims; a role-claim setting (`mcp.auth.roles-claim`) plus a CDI interceptor binding for `@RolesAllowed` on tool beans. Needs care: container security is not active on the MCP endpoint, so this is the library's own check, and the docs must say so. | M |

### P3: investigate, no commitment

| # | Item | Notes | Size |
| --- | --- | --- | --- |
| 11 | **Expose an existing JAX-RS resource method as a tool** (annotation on the resource) | Directly serves "trigger REST endpoints", but the mapping of path/query/body parameters, security context and filters is a design project of its own. [Likely] recommend the pattern "tool bean calls the same service" (as duck-clinic did) and revisit only if users ask. | L |
| 12 | **Async tools** (`CompletionStage`, `@Asynchronous`, `ManagedExecutorService`) | The Tasks extension already covers long-running work; a second async model confuses. | M |
| 13 | **Transactions**: test and document `@Transactional` on tool beans | [Likely] works through the CDI proxy; needs an IT on three servers before we promise it. | S |
| 14 | **More collection shapes**: `Map<K,V>` with non-String keys, `Iterable`, `Stream` | Low demand; `Map<String,V>` covers JSON. | S |
| 15 | **Jakarta Agentic AI binding** | The spec is a draft (1.0.0-M1) with no tool-calling support; nothing to bind to yet. | n/a |

## 3. Decisions (answered 2026-11-18)

- **D1 POJOs:** agreed. Support plain beans with getters up to a depth limit; reject proxies and cycles with a clear error; recommend DTO records in the cookbook.
- **D2 Dependencies:** agreed. Other Jakarta and MicroProfile specs stay optional (reflection, `Instance<Validator>`, `Class.forName`); `mcp-jakarta` keeps `jakartaee-web-api` as its only provided dependency.
- **D3 Order:** agreed as proposed. Item 5 now (README only), 1.1.0 = items 1, 3, 4, then 1.2.0 = items 2 and 6 to 10 as the P-table says.
- **D4 Timing:** nothing starts before 1.0.0. The 1.0.0 release is not before the beginning of December (Central limit). Only documentation that changes no code and no promise may go in earlier: item 5 (`README-cookbook-section.md`). Every other item waits until 1.0.0 is out.

## 4. Open facts to verify before work starts

- Whether `SchemaValidator` formats cover `date-time`, `date`, `time`, `uuid`, `uri`, `duration`.
- Whether `Settings` has other callers that would break when a config source is added.
- Which of the three servers (Payara, WildFly, Liberty) expose Bean Validation and MicroProfile Config by default in the IT setup.
