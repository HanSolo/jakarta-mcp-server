# API review for 1.1.0

What 1.1.0 adds to the surface that 1.0.0 froze, and what each addition commits us to. Tags: [Certain] read in the code, [Likely] inference.

## New stable API

| Type | Change | Commitment |
| --- | --- | --- |
| `eu.hansolo.mcp.api.ToolExceptionMapper` | New functional interface: `Optional<String> message(Throwable failure, ToolCall call)` | [Certain] The signature is frozen until 2.0. Information that a later release wants to pass (the tool's declared type, the request id) goes into `ToolCall`, which already is a record with the overload rule, so the method needs no new parameter. The interface may get `default` methods. Applications implement it as a CDI bean; ordering by `@Priority` is documented behaviour. |
| `eu.hansolo.mcp.api.McpParam` | Targets `METHOD` and `FIELD` added | [Certain] Source and binary compatible: no existing use becomes invalid. On a getter or field only `name` and `description` count; that rule is in the Javadoc. |

## New internal classes (public only for cross-package use, Javadoc says so)

`eu.hansolo.mcp.server.ToolFailures` (public, with `GENERIC` and constructors), `eu.hansolo.mcp.tools.ValueTypes`, `Constraints` and `Beans` (package-private). The README list of internals names `ToolFailures`; the other three are not visible.

## Behaviour that is now part of the promise

- [Certain] The order of the settings sources: system property, environment variable, MicroProfile Config, default.
- [Certain] The failure message rules (README "When a tool throws"): a change of what the model sees for a given exception is a behaviour change and needs a changelog entry.
- [Certain] ISO-8601 text for the value types; the schema keywords for Bean Validation constraints; the property order and omission of `null` for bean results; `Response` status handling.
- [Likely] Bean depth 8 is a number users may come to depend on; it is a constant, not a setting. Making it a setting later is compatible.

## Checked and found fine

- [Certain] No public method signature of the frozen packages changed. `SchemaGenerator.schemaForResult` is new on an internal class.
- [Certain] `Settings` keeps its public methods; the MicroProfile lookup is private.
- [Likely] `ToolResult` and the other sealed types did not change, so a caller that switches over them is unaffected.

## Open

- Whether `ToolExceptionMapper` should also see failures of prompts, resources and completions. Today it covers tools and the work of tasks. A later release can add an overload through a sub-interface without breaking implementers.
