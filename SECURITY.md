# Security policy

## Supported versions

The latest 1.x release gets security fixes. Before 1.0.0, only the latest 0.x release did.

## Reporting a vulnerability

Please do not open a public issue. Use GitHub's private vulnerability reporting: open the **Security** tab of the repository and choose **Report a vulnerability**. Include the version, the application server, a minimal way to reproduce the problem, and what an attacker gains.

The maintainer answers as soon as possible. A fix is released as a patch version, with the advisory published after the release.

## What the library does about the usual risks

This is a map to the places where each decision is written down; it is not a certification.

| Risk | What the library does | Where |
| --- | --- | --- |
| Unauthenticated access | Authentication is **off by default**; the server logs a warning at startup. Turn it on for anything but a local test. | README "Authentication" |
| Forged or stolen tokens | Only `RS256` and `ES256` (never `none` or HMAC). The signature is checked against the keys of the configured JWKS only, and `iss`, `aud` (the resource, RFC 8707), expiry and scopes are checked. Opaque tokens go through RFC 7662 introspection. | README "Authentication" |
| Token passthrough | Tools receive a `Caller` (subject, scopes, claims), never the raw token. | README "Writing tools" |
| Server-side request forgery | A token cannot make the server fetch anything (JWKS and introspection URLs come from the settings, https unless explicitly allowed). Remote `$ref` in argument schemas is not followed. | README "Authentication", "Non-goals" |
| DNS rebinding and cross-site calls | `Origin` is deny-when-present, except for loopback origins; `mcp.allowed-origins` lists browser origins. | README "Configuration", "Decisions worth a second look" |
| Replayed or altered multi round-trip state | `requestState` is HMAC-signed, expires, and can be bound to a context such as the caller (`McpRequestStates.sign(payload, context)`); `require` rejects anything else with -32602. Set `mcp.request-state.secret` to the same value on every node, or states issued by one node are refused by another. | README "Request state in multi round-trip tools" |
| Secrets collected through elicitation | Form-mode requests whose message or fields look like a request for passwords, keys, tokens or card numbers are refused when they are built. Use URL mode for those. | README "Elicitation" |
| Resource exhaustion | Request size (`mcp.max-body-bytes`, 413 above it), open listen streams (`mcp.listen.max`, `mcp.listen.max-per-caller`), running tasks and stored task results (`mcp.tasks.max-*`). Rate limiting is deliberately not part of the library: use a gateway or a JAX-RS filter. | README "Configuration", "Non-goals" |
| Tool misuse by an agent | Tools declare hints (`readOnlyHint`, `destructiveHint`) and scopes (`@McpTool(scopes = ...)`); a tool that must not run unconfirmed can ask for confirmation through elicitation. Audit and policy gates are plain CDI interceptors on the tool bean. | README "Writing tools", "Elicitation" |

## What stays with the application

- Deploying behind TLS, and the choice of authorization server.
- What a tool is allowed to do. A tool runs with the application's own rights; check the caller's scopes and the arguments before touching data.
- Rate limits, audit logs and retention.
- `@RolesAllowed` is not evaluated on tool methods.
