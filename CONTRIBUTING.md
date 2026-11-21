# Contributing

Thank you for helping. This file holds the rules that keep the 1.x API stable. Build and test commands are in the README ("Build and run", "Integration tests and Docker troubleshooting").

## Before you start

- Open an issue for anything beyond a small fix, so the idea can be checked against the non-goals (README "Non-goals") and the backlog (`docs/design/backlog-post-1.0.md`).
- Build with Maven 3.9 or newer and JDK 25 (the bytecode is Java 21). Always use `mvn clean verify`: stale classes in `target/` end up in a WAR and break the deployment.
- `scripts/it.sh` runs the integration tests on Payara, WildFly and Open Liberty and needs Docker. `scripts/release-check.sh` builds sources and Javadoc jars the way a release does; a Javadoc error fails it.

## The API rules

What is stable is listed in the README under "Stable API and internals". From 1.0.0 on:

1. **No incompatible change to a stable class before 2.0.** That covers public and protected signatures, the `mcp.*` settings keys, and the HTTP behaviour of the endpoint.
2. **Records in `api`:** when you add a component, keep the old canonical constructor as an overload (as `ToolCall` did twice), so code that creates the record keeps compiling.
3. **Sealed types** (`ToolResult`, `PromptResult`, `ResourceResult`, `ExternalJobStatus`): applications create and return them but do not `switch` over them, so a variant can be added in a minor release. Say so in the Javadoc of any new one.
4. **Provisional SPI** (`TaskStore`, `TaskRecord`): may change in a minor release if the changelog says so.
5. **New internals** get an "Internal" paragraph in the class Javadoc, as the existing ones have, and are not added to the stable list.
6. **Settings:** a new setting needs a default that keeps the old behaviour, a line in the README "Configuration" table, and a failure at startup (not at the first request) if its value is unusable.
7. **Protocol:** 1.x supports the specification 2026-07-28 only. A change that depends on another revision is a design discussion first.

## A change is done when

- It has tests: a unit test for the logic, and an integration test (`mcp-it`) if it changes what a client sees. Wait for observable state in tests, never for time (`awaitRuns(n)`, not `sleep`).
- Public declarations have Javadoc that says what the caller gets, not how it works.
- `CHANGELOG.md` has an entry under "Unreleased" (Added, Changed or Fixed). A breaking change to a provisional type says "Breaking for ..." and names who is affected.
- The README is changed where it describes the behaviour.
- `mvn clean verify` and `scripts/release-check.sh` pass; for anything near the transport or authentication, `scripts/it.sh --servers payara,wildfly,liberty` as well.

## Style

Follow the code around your change (aligned field declarations, blank lines between groups of members) and do not reformat lines you did not change.

## Security

Do not report vulnerabilities in a public issue. See `SECURITY.md`.
