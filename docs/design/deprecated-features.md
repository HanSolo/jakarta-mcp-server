# Design note: features of 2026-07-28 that this library deliberately leaves out

Status: decided for 0.8.0. Sources: the [2026-07-28 changelog](https://modelcontextprotocol.io/specification/2026-07-28/changelog) (section "Deprecated", SEP-2577 and PR #2858) and the [client registration page](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/client-registration.md).

## Decision

| Feature | Spec status | What the library does |
|---|---|---|
| Logging (`io.modelcontextprotocol/logLevel`, `notifications/message`) | Deprecated | Not implemented. The server never emits `notifications/message`, which is also what the specification requires of a request without `logLevel`. |
| Sampling (`sampling/createMessage`) | Deprecated | No API. A tool can still ask for it through `ToolResult.inputRequired(JsonObject, String)` with hand-built `inputRequests` JSON (the conformance fixtures do). |
| Roots (`roots/list`) | Deprecated | Same as sampling. |
| Dynamic Client Registration (RFC 7591) | Deprecated in favour of Client ID Metadata Documents | The library is a resource server and registers no clients, so there is nothing to implement. |

The specification says these "remain fully functional during the deprecation window but new implementations should not add support for them", and sets a minimum deprecation window of twelve months. [Certain] for the wording; [Likely] that removal is therefore not before mid-2027.

## Why no helper API for sampling and roots

`InputRequests` (and so `ToolResult.ask`) accepts only `Elicitation.Request`, but `ToolResult.inputRequired(JsonObject, String)` takes plain JSON and `ToolCall.inputResponses()` returns the answers as JSON. That is enough for anyone who needs them, and a typed API would be new public surface for a feature scheduled for removal: every method added now is one more to keep or break later.

## What stays our job

- **Never emit `notifications/message`.** True today (nothing in the library writes it). A unit test that scans the output of a streamed `tools/call` and a `subscriptions/listen` stream for that method would keep it true; not written yet.
- **Authorization servers.** For clients without a pre-existing relationship, the specification prefers Client ID Metadata Documents; a server advertises support with `client_id_metadata_document_supported: true` in its authorization server metadata. That is the authorization server's job, not this library's. What this library owes is a correct protected resource metadata document (RFC 9728) that names the authorization server, and token validation. Both have tests.

## When to look at this again

- When a revision after 2026-07-28 moves any of these from Deprecated to Removed: delete the hand-built examples from the README and the conformance fixtures that use them.
- When a user asks for sampling or roots with a concrete use: then add the smallest typed builder that fits, in a minor release.
