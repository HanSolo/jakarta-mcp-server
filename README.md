# Jakarta MCP Server

A stateless [Model Context Protocol](https://modelcontextprotocol.io) server for **Jakarta EE 11** (Payara 7), written for **JDK 25** and compiled to **Java 21** bytecode, so it runs on servers with JDK 21 or newer. It speaks spec revision **2026-07-28**: no `initialize` handshake, no `Mcp-Session-Id`, every request self-describing, so any request can land on any instance behind a round-robin load balancer.

The only dependency is the Jakarta EE 11 Web Profile API (CDI, JAX-RS, JSON-P). No Quarkus, no Helidon, no MicroProfile, no third-party MCP SDK, no JWT library (token validation uses the JDK's own crypto).

[![CI](https://github.com/HanSolo/jakarta-mcp-server/actions/workflows/ci.yml/badge.svg)](https://github.com/HanSolo/jakarta-mcp-server/actions/workflows/ci.yml) · [Issues](https://github.com/HanSolo/jakarta-mcp-server/issues) · License: Apache-2.0

## Status: read this first

- **What has been run:** `mvn verify` of the original server (discover, tools/list, tools/call, MRTR) passed on the author's machine, and the server was exercised with MCP Inspector (`--protocol-era modern`) and curl against Payara Micro.
- **Verified on the author's machine (macOS, Docker Desktop 29, Payara Micro 7.2026.10 Build 18 via `payara/micro:latest`, Keycloak 26.4):** `mvn clean verify` passed with 145 unit tests and 46 integration tests (30 in `McpServerIT` at the time, 34 now with the completion tests, 9 in `McpAuthServerIT` with a test issuer, 7 in `McpKeycloakIT` with a real Keycloak). That covers CDI scanning of `@McpTool` / `@McpResource` / `@McpPrompt` beans, the well-known metadata endpoint, 401/403 challenges, audience binding, MRTR and the `eu.hansolo.mcp` rename.
- **Integration run of 2026-11-10, started 18:03 (`scripts/it.sh --conformance`; the author has seen the summary and the logs), on the clean committed tree `v0.6.0-10-ga4c29f5`:** 150 integration tests per server, none failing, on Payara (`payara/micro:latest`), WildFly (`quay.io/wildfly/wildfly:latest-jdk21`) and Open Liberty (`full-java21-openj9-ubi-minimal`). The only skip is `McpTicketsKeycloakIT` on Liberty (10 tests), by design. Tasks classes on all three servers: `TasksIT` 9, `TasksClusterIT` 5 (PostgreSQL), `TasksClusterMariaDbIT` 5 and `TasksJndiIT` 3 (a JNDI `DataSource` provided by each server). The MCP conformance suite ran in the same run: 196 of 196 on all three servers, with `MCP_TASKS_ENABLED=true`, plus 13 scenarios that the suite does not score for 2026-07-28 (the ten Tasks scenarios and three pending ones), none failing. `mvn clean verify` (unit tests with JUnit 6.1.3 and H2 2.5.252, then the integration tests on Payara) passed the same day. `TasksClusterMySqlIT` (MySQL 8.4 with Connector/J) was added after that run and passed on Payara. Setup: macOS, Docker Desktop 29.9.2, JDK 25 compiling to release 21, Maven 3.9.9, Keycloak 26.4, Testcontainers 2.0.5. `tasks-status-notifications` reports no checks because the suite skips it (see Conformance suite).
- **Real client check:** MCP Inspector 2.14.0 (`--protocol-era modern`) against `mcp-example-tasks`: `build_report` is listed, a call becomes a task, the input request is shown and answered, and cancel works. Against `mcp-example-apps`: the clock page shows the time and its refresh button works; the first attempt showed that the page must send `appInfo` in `ui/initialize`, not `clientInfo`. The steps are in [scripts/client-check/README.md](scripts/client-check/README.md); `scripts/client-check/check.mjs` runs the Tasks flow without a browser.
- **Tickets example:** `mvn clean verify` passes on Payara with Keycloak (178 unit tests at that time, 8 tickets integration tests). Passes on WildFly (reported by the user). **Open Liberty:** the tickets example does not start there without a data source: Liberty creates a container-managed `EntityManagerFactory` for `@PersistenceUnit` and looks for `java:comp/DefaultDataSource` instead of using the `jakarta.persistence.jdbc.*` properties of the example's resource-local unit. The tickets integration test is therefore skipped on Liberty. A `<dataSource id="DefaultDataSource">` in `server.xml` with the H2 jar on the server should fix it (untested).
- **Streaming (progress notifications over SSE):** passes on Payara (`mvn clean verify`, 4 streaming integration tests, including one that proves events arrive while the tool is still running and one that resets the connection and checks that the tool is told). The first version, a JAX-RS `StreamingOutput`, was buffered by Jersey; the servlet-based version fixed that. WildFly and Open Liberty: passed in the run of 2026-11-10 (see above).
- **Notifications (`subscriptions/listen`, list-changed, resource updates):** passes on Payara (13 unit tests, 3 integration tests in `McpServerIT`, 2 in `McpTicketsKeycloakIT`). WildFly and Open Liberty: passed in the run of 2026-11-10 (see above).
- **Async listen mode (`mcp.listen.async=true`, virtual threads):** passes on Payara (`mvn clean verify`: 196 library unit tests and 70 integration tests, including `McpAsyncListenIT`: the acknowledgement and notification, 60 open streams while ordinary requests are still answered, and a stream that lives past 40 seconds). Every request now goes through a suspended JAX-RS response, which the whole suite exercised. Passed on WildFly and Open Liberty in the run of 2026-11-10 (`McpAsyncListenIT`, 3 tests); see also the async section below.
- **Since 0.4.0 (argument validation, opaque tokens, dynamic registration, the notification bridge):** covered by unit tests, by the MCP conformance suite against Payara Micro in CI, and by the integration tests of the run above on all three servers: `ArgumentValidationIT`, `DynamicRegistrationIT`, `IntrospectionIT` (against Keycloak 26.4) and `NotificationBridgeIT` (two containers). They use the test-only `mcp-it-fixture` WAR. One gap: the audience test in `IntrospectionIT` asserts only the 401, not why Keycloak or the server refused the token.
- **Not yet run:** the Docker Compose setup. After renaming packages or moving files, always build with `mvn clean verify`: stale classes in `target/` end up in the WAR and the deployment fails with a `ClassNotFoundException`.
- **Unverified:** behaviour with a real identity provider (see the Keycloak item above; any other provider is untested). `latest` is a moving tag: pin the image with `-Dmcp.image=payara/micro:<tag>` once you have checked the tag name on Docker Hub.
- **2026-07-28 clients only.** Clients that still require the `initialize` handshake (2025-11-25 and earlier) get HTTP 400 naming the supported version.
- **Authentication is off by default** (see below). With it off, anyone who can reach the endpoint can call every tool, and the server logs a warning at startup.

## Layout

| Module | Contents |
| --- | --- |
| `mcp-jakarta` | Library JAR: tool, resource and prompt APIs (`@McpTool`, `@McpResource`, `@McpPrompt` and the matching handler interfaces), protocol handling (`McpRequestHandler`, container-independent), OAuth resource-server code (`auth`), JAX-RS resources, CDI wiring |
| `mcp-jakarta-tasks-jdbc` | Optional JAR: `JdbcTaskStore`, a `TaskStore` in a relational database (see [Tasks](#tasks-experimental)) |
| `mcp-example` | WAR deployed at the context root: annotated tools (`echo`, `add`, `repeat`), a handler-style tool (`greet`, multi round-trip), a resource (`config://server/info`), a template (`greeting://{name}`) and a prompt (`summarize`) |
| `mcp-example-tickets` | A realistic example: a support-ticket service with a database, scopes and a confirmation step (see [The tickets example](#the-tickets-example)). Needs authentication |
| `mcp-example-conformance` | A fixture WAR with the tools, prompts and resources the official conformance suite expects (see [Conformance suite](#conformance-suite)). A test aid, not an example to copy |
| `mcp-example-apps` | The MCP Apps example: `show_clock` returns text and structured time and names a `ui://` page that supporting clients display (see [MCP Apps](#mcp-apps)) |
| `mcp-example-tasks` | The Tasks example: `build_report` runs as a task with status messages, a question to the client and cancellation; includes `demo.sh` (see [Tasks](#tasks-experimental)) |
| `mcp-it-fixture` | A test-only WAR: an **unauthenticated** admin endpoint (add and remove tools, announce changes, receive a change), a bridge that POSTs changes to peer instances, and a tool with a nested `oneOf` schema. Used by the integration tests; never deploy it |
| `mcp-it` | Integration tests: starts the example WAR in a Payara Micro, WildFly or Open Liberty container with Testcontainers |

## Supported servers

| Server | Status | How the integration tests run it |
| --- | --- | --- |
| Payara 7 (Micro) | **Verified** by the author (7.2026.10 Build 18) | `-Dmcp.server=payara` (default), image `payara/micro:latest` |
| WildFly | **Verified** on `quay.io/wildfly/wildfly:latest-jdk21` (WildFly 42.0.0.Final, RESTEasy 7.0.5). The first attempt failed with `UnsupportedClassVersionError` because the WAR was compiled for Java 25; the default is now Java 21 bytecode and the suite passes | `-Dmcp.server=wildfly`, image `quay.io/wildfly/wildfly:latest-jdk21` |
| Open Liberty | **Verified** (run of 2026-11-09, logs seen by the author). The first attempt failed only because the image tag I had guessed did not exist. `mcp-it/src/test/resources/liberty/server.xml` enables `jakartaee-11.0` | `-Dmcp.server=liberty`, image `icr.io/appcafe/open-liberty:full-java21-openj9-ubi-minimal` |

The code uses only Jakarta EE 11 Web Profile APIs and has now run on three servers (Payara, WildFly, Open Liberty). Open Liberty also ships its own MCP support (`mcp-1.0`); use this library when you need portability across servers or the 2026-07-28 stateless protocol. The image tags above are defaults chosen from the vendors' naming schemes; if one does not exist, override it with `-Dmcp.image=...`. If you run these and something breaks, the failing assertion plus the container log (printed on startup failure) is the useful bug report. CI (`.github/workflows/ci.yml`) runs all three; All three are required to pass.

## Adding the library to your own application

1. Depend on the library (Jakarta EE 11 APIs stay `provided`; the server supplies JSON-P):

   ```xml
   <dependency>
       <groupId>eu.hansolo.mcp</groupId>
       <artifactId>mcp-jakarta</artifactId>
       <version>0.7.0</version>
   </dependency>
   ```

   `0.7.0` is the current release, on Maven Central (the JDBC task store `eu.hansolo.mcp:mcp-jakarta-tasks-jdbc` has the same version). To use the development state instead, run `mvn install` in this project and depend on the `-SNAPSHOT` version in the POMs. See [CHANGELOG.md](CHANGELOG.md) for what changed in each release.
2. Register the endpoint classes in your JAX-RS `Application` (see `ExampleApplication`): `McpResource` and, if you use authentication, `ProtectedResourceMetadataResource`. The endpoint is `<context root>/mcp`. OAuth metadata discovery (RFC 9728) needs the application at the host root.
3. Add `@McpTool`, `@McpResource` and `@McpPrompt` methods to your CDI beans (any bean-defining scope; with `bean-discovery-mode="annotated"` the class needs a scope annotation).
4. Configure through system properties or environment variables (see the table below). Authentication is off by default.

## Build and run

```bash
mvn verify                                  # Maven 3.9.0+ and JDK 21 or newer (checked at build start); unit tests, and integration tests if Docker is available
mvn verify -Dmaven.compiler.release=25      # only if every server you deploy to runs JDK 25
mvn verify -Dmcp.image=payara/micro:<tag>   # pin the image (-Dpayara.image still works for Payara)
mvn verify -Dmcp.server=wildfly              # or liberty; see "Supported servers"

java -jar payara-micro-7.x.jar --deploy mcp-example/target/mcp-example.war
```

Integration tests (`*IT`) run in the `verify` phase through Failsafe and are **skipped, not failed, when Docker is unavailable**.

`scripts/it.sh` runs the integration tests against one or more servers (`scripts/it.sh --servers wildfly`; default is all three) and does not trust `BUILD SUCCESS`: it checks that Docker is usable for Testcontainers (it adds `api.version` to `~/.docker-java.properties` when the engine needs it, and points `DOCKER_HOST` at the active docker context when there is no `/var/run/docker.sock`), pulls the images, runs `mvn clean verify -Dmcp.server=<server>`, reads the Failsafe reports and fails when a test class was skipped, did not run, or failed. Logs, reports and a `summary.txt` (with the image digests) go to `it-logs/<timestamp>/`. `scripts/it.sh --conformance-tasks` runs the conformance suite alone with the Tasks extension on and without `expected-failures.yml`, to see how the `tasks-*` scenarios really fare. `scripts/it.sh --conformance` also runs the MCP conformance suite (`npx @modelcontextprotocol/conformance@alpha`, Node 22+) against the `mcp-example-conformance` WAR on each selected server, with `expected-failures.yml`; `--only-conformance` skips the integration tests. The conformance part ran on all three servers on 2026-11-09 and again on 2026-11-10 (196 checks passed, 0 failed, with the Tasks extension on; the CI job uses Payara only). The conformance container always starts with the Tasks extension on. Run it from a checkout of the version you want to test. It was written against stand-in `docker` and `mvn` commands and has since run for real several times: macOS, Docker Desktop 29.9.2, all three servers.

### Integration tests and Docker troubleshooting

If the log says `Tests run: 0` for `McpServerIT` and `McpAuthServerIT`, the tests were skipped, and `BUILD SUCCESS` proves nothing about the Payara wiring. A passing run shows `Tests run: 34` for `McpServerIT` and `Tests run: 9` for `McpAuthServerIT` and 7 for `McpKeycloakIT`, both with `Skipped: 0`. The `[mcp-it]` line in the log says why they were skipped, and the test classpath includes `slf4j-simple` so Testcontainers explains itself.

**Newer Docker engines (observed with Docker Desktop 29, API 1.56):** the Docker client bundled in Testcontainers 2.0.1 talks API 1.32, and the engine answers `client version 1.32 is too old. Minimum supported API version is 1.40`. Testcontainers then reports Docker as unavailable even though `docker info` works. Tell the client to use a newer API version once per machine:

```bash
echo "api.version=1.44" > ~/.docker-java.properties
```

**Docker socket not at the default path** (Docker Desktop with the default socket disabled, Colima, Rancher Desktop): Testcontainers usually finds `~/.docker/run/docker.sock` itself. If it does not, export `DOCKER_HOST=unix://<path from docker context ls>` in the shell (and in the IDE run configuration) that starts Maven.

The first run pulls the Ryuk image and the server image, which takes a few minutes.

The endpoint is `http://localhost:8080/mcp` (the WAR is deployed as `ROOT.war`, and `glassfish-web.xml` / `jboss-web.xml` set the context root to `/`). With MCP Inspector, pass `--protocol-era modern`: it defaults to the legacy handshake, which this server rejects. With curl:

```bash
curl -s http://localhost:8080/mcp \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -H 'MCP-Protocol-Version: 2026-07-28' -H 'Mcp-Method: tools/call' -H 'Mcp-Name: echo' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"echo","arguments":{"message":"hi"},"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}'
```

## The tickets example

`mcp-example` is a hello-world. `mcp-example-tickets` shows what a real service looks like: an IT help desk that an AI assistant can search, read, file tickets in and close, with the permissions enforced by your identity provider.

**What an assistant can do with it**

| | Name | What it does | Needs scope |
| --- | --- | --- | --- |
| Tool | `search_tickets` | Find tickets by text and status, 20 per page (`limit` / `offset`) | `tickets:read` |
| Tool | `get_ticket` | Show one ticket | `tickets:read` |
| Tool | `create_ticket` | File a ticket, recorded under the caller's identity | `tickets:write` |
| Tool | `close_ticket` | Close a ticket, **after the user confirms** | `tickets:write` |
| Resource | `ticket://{id}`, `ticket://stats` | A ticket as JSON, and open/closed counts | `tickets:read` |
| Prompt | `triage` | "Assess this ticket and suggest next steps" | `tickets:read` |
| Completion | ticket numbers | Suggests numbers while the user types | `tickets:read` |

A token with only `tickets:read` sees just the read tools. Without any ticket scope the tool list is empty. 120 sample tickets are created at startup in an in-memory H2 database, so there is nothing to set up.

**Try it** (needs Docker; builds the WAR, starts Keycloak and the server):

```
mvn -DskipTests package
docker compose -f keycloak/docker-compose.yml -f keycloak/docker-compose.tickets.yml up
TOKEN=$(curl -s http://localhost:8180/realms/mcp/protocol/openid-connect/token \
  -d grant_type=password -d client_id=mcp-cli -d username=alice -d password=alice \
  -d "scope=mcp:tools tickets:read tickets:write" | jq -r .access_token)
```

Then use `$TOKEN` as in the Keycloak section below (MCP Inspector or curl). Ask for fewer scopes to watch the tool list shrink.

**Where to look in the code**

- `TicketTools`, `TicketResources`, `TicketPrompts`, `TicketCompletions`: the whole MCP side, plain annotated classes with `@McpTool(scopes = ...)` and friends.
- `TicketStore` / `JpaTicketStore`: the database side. The MCP classes only see the interface, which is why the unit tests run without a database.
- `close_ticket`: the confirmation flow. The first call returns "input required"; the client asks the user and repeats the call with the answer. The server keeps nothing in between. A client that cannot ask the user gets an error, never a silent close.

**Simplifications to know about:** the database is embedded H2 and the persistence unit is resource-local, so the example runs on any server without configuring a datasource. A real application would use the server's JTA datasource. Ticket ids come from a sequence rather than `IDENTITY`, because EclipseLink's `IDENTITY` DDL is rejected by H2 2.x.

## Writing tools

### Annotations (recommended)

Put `@McpTool` on public methods of any CDI bean. Compile with `-parameters` (the parent POM does) or name parameters with `@McpParam(name = ...)`.

```java
@ApplicationScoped
public class Calculator {

    public record Sum(@McpParam(description = "a + b") double sum) {
    }

    @McpTool(description = "Adds two numbers.", readOnlyHint = McpTool.Hint.TRUE)
    public Sum add(@McpParam(description = "First addend") double a, double b) {
        return new Sum(a + b);
    }

    @McpTool(scopes = "files:write")
    public String save(String path, Optional<String> comment, Caller caller) {
        if (path.contains("..")) {
            throw new ToolExecutionException("Path must not contain '..'");   // shown to the model as isError
        }
        return "saved by " + caller.subject();
    }
}
```

| Aspect | Supported |
| --- | --- |
| Parameters | `String`, booleans, integer and floating types and wrappers, `BigInteger`, `BigDecimal`, enums, `List`/`Set`/`Collection`/arrays, `Map<String,V>`, records (nested objects; a record that refers to itself, directly or through other records, collections or `Optional`, is described with `$defs`/`$ref`, see Argument validation), `JsonObject`/`JsonArray`/`JsonValue`, `Optional<T>` for optional arguments |
| Injected parameters (not in the schema) | `ToolCall` (full request context, MRTR input), `Caller` (who is calling) |
| Return values | `String` and scalars (text), record / `Map` / `JsonObject` (structured content plus a text copy; records also publish an `outputSchema`), `void` (empty result), `ToolResult` (full control, including `inputRequired`) |
| Errors | `ToolExecutionException` is shown to the model; any other exception is logged and replaced by a generic message |
| Arguments | Types, ranges and nested structure are checked during conversion; errors name the offending path (`Argument 'times' must be an integer`) |

Rules: the method must be public and non-static (the call goes through the CDI proxy, so interceptors and scopes apply); one bean per class; use `Optional` for nullable record components in results (a `null` would violate the generated schema). Mistakes in declarations fail the deployment with the class and method name.

### Resources and prompts

```java
@ApplicationScoped
public class MyResources {

    @McpResource(uri = "config://server/info", description = "What this server is.")
    public ServerInfo info() { return new ServerInfo(...); }            // record -> JSON text

    @McpResource(uriTemplate = "greeting://{name}", mimeType = "text/plain")
    public String greeting(String name) { return "Hello, " + name + "!"; }   // template variable by parameter name
}

@ApplicationScoped
public class MyPrompts {

    @McpPrompt(description = "Summarizes a text.")
    public String summarize(@McpParam(description = "The text") String text, Optional<String> style) { ... }   // one user message
}
```

- Resources: return `String`, `byte[]`, a record/`Map`/`List`/JSON-P value (as JSON text), `ResourceContent`, `List<ResourceContent>` or `ResourceResult` (for caching hints or multi round-trip). Template syntax is `{name}` (one segment) and `{+name}` (may contain `/`). Variable values are client input: validate them, and reject path traversal if you map them to files. Fixed resources win over templates, then templates are tried in the alphabetical order of their template strings.
- Prompts: arguments are strings by protocol definition (`String` required, `Optional<String>` optional). The server rejects missing required and unknown arguments with -32602 before your method runs. Return `String`, `PromptMessage`, `List<PromptMessage>` or `PromptResult`.
- `resources/read` results carry `ttlMs` 0 and `cacheScope` `private` (`public` with authentication off) unless your `ResourceResult` says otherwise. `prompts/get` has no cache hints. An unknown resource is -32602 with `data.uri`, never an empty `contents`.
- For full control implement `McpResourceHandler`, `McpResourceTemplateHandler` or `McpPromptHandler` in a CDI bean.
- `resources` and `prompts` appear in `server/discover` capabilities only when something is registered; otherwise their methods answer 404 method-not-found.

### Handler interface

For full control implement `McpToolHandler` in a CDI bean. `GreetTool` shows Multi Round-Trip Requests: it returns `ToolResult.inputRequired(...)` to ask the client for input, and the client retries with `inputResponses`. A `requestState` string comes back from the client, so authenticate it (sign or encrypt it) before trusting it.

Tools must not rely on state between calls. If a tool needs to continue across calls, return an explicit handle for the model to pass back.

### Request state in multi round-trip tools

`requestState` travels through the client, which can read, change and replay it, so a tool must not trust it as it arrives. Inject `McpRequestStates` and let it sign what you hand out:

```java
@Inject McpRequestStates states;
...
return ToolResult.inputRequired(request, states.sign("step=2;name=" + name, call.name() + "|" + call.caller().subject()));
...
String payload = states.require(call.requestState(), call.name() + "|" + call.caller().subject());  // -32602 if altered, expired or issued for someone else
```

The state is signed (HMAC-SHA256) and expires after `mcp.request-state.ttl-ms`; it is not encrypted, so keep secrets out of it. The optional context string binds a state to a tool and caller.

## Authentication

The server can act as an OAuth 2.1 **resource server** as the MCP authorization spec describes: it validates bearer access tokens and tells clients where to get them. It is not an authorization server; you need one (Keycloak, Entra ID, Auth0, ...) that issues access tokens: JWTs with a published JWKS, or opaque tokens that it can introspect (see below).

Enable it with environment variables or system properties:

```bash
MCP_AUTH_ENABLED=true
MCP_AUTH_RESOURCE=https://mcp.example.com/mcp          # the endpoint URL as clients see it
MCP_AUTH_ISSUER=https://auth.example.com/realms/main
MCP_AUTH_JWKS_URI=https://auth.example.com/realms/main/protocol/openid-connect/certs
MCP_AUTH_SCOPES_SUPPORTED=mcp:tools,files
MCP_AUTH_REQUIRED_SCOPES=mcp:tools
```

What it does:

- Rejects requests without a valid `Authorization: Bearer` token with **401** and `WWW-Authenticate: Bearer resource_metadata="...", scope="..."` (RFC 9728, RFC 6750). It authenticates before reading the body.
- Validates the JWT: only `RS256` and `ES256` (never `none` or HMAC), signature against keys from the configured JWKS only (a token cannot make the server fetch anything), `iss`, **`aud` equal to this resource** (RFC 8707, so tokens issued for another API are refused: no token passthrough), `exp` and `nbf` with a clock-skew allowance. RSA keys under 2048 bits are ignored.
- Keys are cached; an unknown `kid` triggers at most one refresh per `mcp.auth.jwks-min-refresh-seconds`; a failed refresh keeps the old keys.
- **403** `insufficient_scope` with the scopes needed, for `mcp.auth.required-scopes` (every request) and for `@McpTool(scopes = ...)` / `McpToolHandler.requiredScopes()` (per call, checked before argument validation). A broader scope implies narrower ones: `files` satisfies `files:read`.
- Serves `/.well-known/oauth-protected-resource[/path]` (RFC 9728). **This requires deployment at the host root** (ROOT.war, or context root `/`), or a reverse proxy that maps it.
- Tools receive a `Caller` (subject, scopes, all claims) per call, never the raw token.
- Misconfiguration fails the deployment: auth enabled with a missing issuer, resource or JWKS URI, a non-https JWKS URI (unless `mcp.auth.jwks-allow-http=true`, for local tests), or tools that declare scopes while auth is disabled.

Notes: `tools/list`, `resources/list`, `resources/templates/list` and `prompts/list` show a caller only what their scopes allow. When any primitive declares scopes (and authentication is on), list results use `cacheScope` `private` so shared caches cannot hand one caller's list to another; otherwise `public`. Reading or getting something you lack the scope for is a 403 `insufficient_scope` challenge. Scopes on resources and prompts follow the same rule as for tools: declaring them with authentication off fails the deployment. For opaque tokens see "Opaque tokens" below; for anything else, provide your own `McpAuthenticator` bean (`@Alternative @Priority`). Dynamic client registration and the authorization-server side are out of scope. Tokens are not revocable between requests beyond their expiry, so keep access-token lifetimes short.

### Opaque tokens (introspection)

If your authorization server issues opaque access tokens (random strings, no JWT), set the introspection endpoint instead of the JWKS URI (RFC 7662):

```bash
MCP_AUTH_ENABLED=true
MCP_AUTH_RESOURCE=https://mcp.example.com/mcp
MCP_AUTH_ISSUER=https://auth.example.com
MCP_AUTH_INTROSPECTION_URI=https://auth.example.com/oauth2/introspect
MCP_AUTH_INTROSPECTION_CLIENT_ID=mcp-server          # this server's own client at the endpoint
MCP_AUTH_INTROSPECTION_CLIENT_SECRET=...
```

Set either `mcp.auth.jwks-uri` or `mcp.auth.introspection-uri`, not both; with introspection every token goes to the endpoint, JWT or not. The server posts `token=...` with HTTP Basic client credentials and trusts the answer only if it says `"active": true`; then it checks `iss` (if present), **`aud` equal to this resource** (a missing `aud` is refused unless `mcp.auth.introspection-require-audience=false`, a wrong one never is), `exp` and `nbf`. `sub`, `scope` and the other fields of the answer become the `Caller`.

- **Caching:** answers are kept for `mcp.auth.introspection-cache-seconds` (default 30; 0 asks for every request), keyed by a hash of the token, and never past the token's own `exp`. That is also how long a revoked token can still work. A refusal is cached for at most 5 seconds.
- **When the endpoint is down** (no connection, timeout, HTTP error, unusable answer) the request fails with **503**, not 401, so a client with a good token does not start a new login. Check the server log if the endpoint rejects this server's own credentials (it logs that, never the token).
- **Load:** every new token costs one request to your authorization server, so a flood of garbage tokens reaches it. Put a rate limit in front of the MCP endpoint if it is exposed to the internet.
- The endpoint must be https unless `mcp.auth.introspection-allow-http=true` (tests only). The URL comes from configuration only; redirects are not followed.

### Trying it with Keycloak

`keycloak/docker-compose.yml` starts Keycloak (realm `mcp` imported from `keycloak/mcp-realm.json`) and the example server wired to it. Build the WAR first, then:

```bash
mvn -DskipTests package
docker compose -f keycloak/docker-compose.yml up

TOKEN=$(curl -s http://localhost:8180/realms/mcp/protocol/openid-connect/token \
  -d grant_type=password -d client_id=mcp-cli -d username=alice -d password=alice -d scope=mcp:tools | jq -r .access_token)
```

Use `$TOKEN` as `Authorization: Bearer ...` (MCP Inspector: custom headers, with `--protocol-era modern`). The realm defines the scopes `mcp:tools`, `files`, `files:read`, `tickets:read` and `tickets:write`, an audience mapper that puts the server's resource identifier (`http://localhost:8080/mcp`) into `aud`, and three clients: `mcp-test` and `mcp-no-audience` (client credentials, used by the integration test) and `mcp-cli` (password grant, for curl). Development settings only: HTTP, throwaway passwords.

The two things that go wrong with a real issuer are the issuer URL and the audience. Keycloak puts the URL it was *told* it lives at (`KC_HOSTNAME`) into `iss`, regardless of how a client reached it; the MCP container reaches it by a different name (`keycloak:8080`) and therefore needs `mcp.auth.jwks-uri` set separately from `mcp.auth.issuer`. And Keycloak does not add your server to `aud` by itself: the `mcp-audience` client scope does that, and a client without it gets 401 `invalid_token`. `McpKeycloakIT` checks both, plus the 401 and 403 challenges, with a Keycloak container (`-Dkeycloak.image=...` overrides the image, default `quay.io/keycloak/keycloak:26.4`).

Not covered: the browser-based authorization-code flow with a real MCP client. Client registration is the authorization server's job, so this library has nothing to implement for it; 2026-07-28 prefers Client ID Metadata Documents (an authorization server advertises them with `client_id_metadata_document_supported`) and deprecates dynamic client registration (RFC 7591). Keycloak restricts anonymous registration by policy; whether a given client completes the flow against this server and Keycloak has not been tested.

## Releasing to Maven Central

For maintainers. Uses the Central Portal; the namespace `eu.hansolo` must be verified for your account.

1. A server `central` in `~/.m2/settings.xml` holding a portal user token, and a GPG key whose public half is on a keyserver.
2. Run `scripts/release-check.sh` (sources and Javadoc jars without signing; a Javadoc error fails the build, which the real upload would also do). Then set a release version (`mvn versions:set -DnewVersion=<version> -DgenerateBackupPoms=false`), commit, tag.
3. `mvn -Prelease clean deploy -pl .,mcp-jakarta,mcp-jakarta-tasks-jdbc` signs and uploads the parent POM, `mcp-jakarta` and `mcp-jakarta-tasks-jdbc` (jar, sources, Javadoc). The `-pl` matters: the plugin bundles every module that is part of the build, so without it the example WARs and `mcp-it` would be uploaded too.
4. The bundle is validated but not published: open the portal, check it and press Publish. Releases cannot be deleted afterwards. Set `autoPublish` to `true` in the root `pom.xml` to skip this step.
5. Set the next `-SNAPSHOT` version.

The profile published 0.1.0. Check the `central-publishing-maven-plugin` version in the root `pom.xml` against the current release before each release.

## Configuration

Each setting is a system property, or the same name upper-cased with `.` and `-` turned into `_` as an environment variable.

| Setting | Default | Purpose |
| --- | --- | --- |
| `mcp.allowed-origins` | none | Comma-separated browser origins allowed to call the endpoint, or `*`. Requests without an `Origin` header (non-browser clients) always pass, and so do loopback origins (`http(s)://localhost`, `127.0.0.1` or `[::1]`, any port). |
| `mcp.max-body-bytes` | 1048576 | Request size limit (413 above it) |
| `mcp.cache-ttl-ms` | 60000 | `ttlMs` hint on `server/discover` and the list methods |
| `mcp.validation.formats` | false | Check the `format` keyword of tool input schemas (`date-time`, `date`, `time`, `duration`, `email`, `hostname`, `ipv4`, `ipv6`, `uri`, `uri-reference`, `uuid`, `json-pointer`, `regex`; other names are accepted). Off by default, as in JSON Schema 2020-12, where `format` is an annotation |
| `mcp.page-size` | 0 | Maximum items per page for `tools/list`, `resources/list`, `resources/templates/list` and `prompts/list`; 0 returns each list whole |
| `mcp.listen.max` | 100 | Most `subscriptions/listen` streams open at once on this instance; each holds one thread (a container thread unless `mcp.listen.async` is on). 0 switches subscriptions off |
| `mcp.listen.max-per-caller` | 10 | Most streams one caller (token subject) may hold open |
| `mcp.listen.async` | false | Run `subscriptions/listen` streams on virtual threads, so they do not hold container request threads (see Notifications) |
| `mcp.listen.executor` | none | JNDI name of a managed executor to run those streams on instead of JDK virtual threads |
| `mcp.request-state.secret` | random per instance | HMAC key for `McpRequestStates` (see Writing tools). Set the same value on every node of a cluster, or a state issued by one node is refused by another |
| `mcp.request-state.ttl-ms` | 600000 | How long a signed request state stays valid |
| `mcp.tasks.enabled` | false | Turn on the Tasks extension (see Tasks) |
| `mcp.tasks.ttl-ms`, `mcp.tasks.poll-interval-ms` | 3600000, 2000 | How long a task is kept (counted from its creation) and the polling interval suggested to clients; a task can override both |
| `mcp.tasks.max-result-bytes`, `mcp.tasks.max-running` | 1048576, 100 | Largest stored result (a bigger one fails the task) and most tasks one instance runs at once (503 above it) |
| `mcp.tasks.executor` | `java:comp/DefaultManagedExecutorService`, then virtual threads | JNDI name of the executor that runs the work |
| `mcp.tasks.heartbeat-ms`, `mcp.tasks.lease-ms`, `mcp.tasks.sweep-ms` | 10000, 30000, 30000 | Lease renewal, lease length and how often lost and expired tasks are looked for |
| `mcp.tasks.max-attempts` | 3 | Most runs of a restartable task, the first included; 1 switches restarts off |
| `mcp.tasks.notify-poll-ms` | 2000 | How often a `subscriptions/listen` stream that watches tasks looks in the store for changes made on other instances |
| `mcp.tasks.input-poll-ms`, `mcp.tasks.input-timeout-ms` | 500, 3600000 | How often work that waits for input looks for the answer, and how long it waits at most |
| `mcp.tasks.datasource` or `mcp.tasks.jdbc.url`, `.user`, `.password` | none | JDBC task store (module `mcp-jakarta-tasks-jdbc`): JNDI name of a `DataSource`, or a JDBC URL |
| `mcp.tasks.jdbc.table`, `mcp.tasks.jdbc.create-schema` | `mcp_tasks`, false | Table name and whether to create the table at startup |
| `mcp.listen.keepalive-ms` | 15000 | How often an idle stream gets a comment line (`subscriptions/listen` streams and streamed `tools/call`); this is also how a vanished client is noticed and its thread released |
| `mcp.server.name`, `mcp.server.version` | from build | Identity returned in `_meta` and discovery |
| `mcp.instructions` | none | Guidance for the model, returned by `server/discover` |
| `mcp.auth.enabled` | false | Turn on bearer-token authentication |
| `mcp.auth.resource` | required if enabled | Canonical URL of the MCP endpoint; default audience; basis of the metadata URL |
| `mcp.auth.issuer` | required if enabled | Expected `iss` |
| `mcp.auth.jwks-uri` | required if enabled, unless introspection is used | Where signing keys are published (https) |
| `mcp.auth.introspection-uri` | none | RFC 7662 endpoint for opaque tokens; replaces `mcp.auth.jwks-uri` (https) |
| `mcp.auth.introspection-client-id`, `mcp.auth.introspection-client-secret` | none | This server's credentials at the endpoint (HTTP Basic) |
| `mcp.auth.introspection-cache-seconds` | 30 | How long an answer is reused; 0 = ask every time |
| `mcp.auth.introspection-require-audience` | true | Refuse answers without `aud`; false accepts a missing `aud` (never a wrong one) |
| `mcp.auth.introspection-allow-http` | false | Allow a plain-http endpoint (tests only) |
| `mcp.auth.audience` | the resource | Expected `aud` |
| `mcp.auth.authorization-servers` | the issuer | Advertised in the metadata document |
| `mcp.auth.scopes-supported` | none | Advertised in the metadata document and in the 401 challenge |
| `mcp.auth.required-scopes` | none | Scopes every request must carry |
| `mcp.auth.clock-skew-seconds` | 60 | Tolerance for `exp` / `nbf` |
| `mcp.auth.jwks-cache-seconds` | 300 | How long fetched keys are trusted without a refresh |
| `mcp.auth.jwks-min-refresh-seconds` | 30 | Minimum gap between JWKS fetches |
| `mcp.auth.jwks-allow-http` | false | Allow a plain-http JWKS URI (tests only) |

## What is implemented

`server/discover`, `tools/list`, `tools/call`, `resources/list`, `resources/templates/list`, `resources/read`, `prompts/list`, `prompts/get`, `completion/complete`; per-request `_meta` validation; `MCP-Protocol-Version`, `Mcp-Method` and `Mcp-Name` validation (including the `=?base64?...?=` form); `UnsupportedProtocolVersion`, `HeaderMismatch` and `MissingRequiredClientCapability` errors; `resultType`; `ttlMs`/`cacheScope`; MRTR (`input_required`); `Origin` check; 405 for GET/DELETE; stale `Mcp-Session-Id` ignored; OAuth resource-server behaviour and the `@McpTool` layer described above.

## Completion

`completion/complete` suggests values while a user fills in a prompt argument or a resource template variable. Declare a source with `@McpComplete` on a method of a CDI bean:

```java
@McpComplete(prompt = "summarize", argument = "style")
public CompletionResult styles(String typed) {
    return CompletionResult.matching(List.of("bullet points", "one sentence"), typed);   // case-insensitive prefix match
}

@McpComplete(resourceTemplate = "greeting://{name}", argument = "name")
public List<String> names(String typed, CompletionCall call) { ... }   // call.contextArguments() = values already chosen
```

Or implement `McpCompletionHandler`. The `completions` capability appears in `server/discover` only when at least one source exists, and the server refuses to start if a source points at a prompt, template or argument that does not exist. Arguments without a source complete to an empty list. At most 100 values are returned (`hasMore` is set when more existed). Inputs are validated (reference shape, value and context sizes). The scopes of the prompt or template apply to its completions, plus any `scopes` on the source itself: suggestions can leak data just like reads, so a source must never suggest what the caller may not see. Rate limiting, which the spec recommends, is **not** done in this library; put it in front (gateway, filter). Resource references must name a registered template string exactly; fixed resources and URIs that merely match a template are rejected as unknown.

## Streaming

A `tools/call` can send progress while it runs. The server answers with Server-Sent Events (`text/event-stream`) when **both** hold:

- the request carries `_meta.progressToken` (the client wants progress), and
- the `Accept` header lists `text/event-stream` explicitly (a bare `*/*` or `text/*` does not count).

Otherwise the reply is one JSON object, exactly as before, and progress calls in the tool are ignored. A tool reports progress like this:

```java
@McpTool(description = "Re-indexes everything.")
public String reindex(ToolCall call) {
    for (int i = 1; i <= 100; i++) {
        doOneUnit();
        call.progress(i, 100.0, "Indexed " + i + " of 100");   // no-op unless the client asked for progress
    }
    return "done";
}
```

What the client sees: zero or more `notifications/progress` events, then the JSON-RPC response as the last event, then the stream ends. Details worth knowing:

- **Errors before the tool runs keep their HTTP status.** Authentication, headers, unknown tool, missing scope and invalid arguments are checked first and answered as ordinary JSON (400, 401, 403, or 200 with a JSON-RPC error). Only after the tool has started is the status fixed at 200; a failure then arrives as the final event (an error result or a JSON-RPC error).
- **Closing the connection cancels the request.** The server notices on its next write: `call.progress(...)` then throws `RequestCancelledException`, which a tool should let propagate, and `call.isCancelled()` turns true. While the tool runs, the server also writes an SSE comment line whenever the stream has been quiet for `mcp.listen.keepalive-ms` (default 15 s), so a tool that reports no progress is noticed as cancelled within about one interval; it can poll `call.isCancelled()` in its loops. The server sends nothing more for a cancelled request.
- **Progress must increase** with every report of one call (the spec requires it); a repeated or smaller value throws `IllegalArgumentException`, which counts as a tool failure.
- **Only `tools/call`.** Other methods are always plain JSON. There is no resumption (`Last-Event-ID` is ignored, as the spec says). Long-lived notification streams are `subscriptions/listen` (see Notifications).
- **The tool runs on the request thread** while the response is open, so a long call holds a container thread. Keep the call as short as the work allows. The keep-alive comment lines (see above) stop a proxy with an idle timeout from cutting a long silent call, as long as `mcp.listen.keepalive-ms` is below that timeout; a call that does not stream (no progress token, or no `text/event-stream` in `Accept`) has no keep-alive. Each streamed call starts one short-lived virtual thread for it.
- **Status of the keep-alive:** unit-tested (3 tests in `McpStreamingTest`: comments during a quiet call and none after the result, a failed comment write cancelling the call, no comment for a fast call). `KeepAliveIT` (2 tests, fixture tool `quiet`, interval 200 ms: comments arrive while the tool runs, not at the end) is written and has not been run.
- **How it is written:** straight to the servlet response (needs the Servlet API, part of the Web Profile), not through JAX-RS, because Jersey ignores `flush()` on a `StreamingOutput` and delivers the whole entity at the end. The server flushes after every event and sends `Cache-Control: no-cache, no-transform` and `X-Accel-Buffering: no`. A reverse proxy in front may still buffer; turn that off for `/mcp`.

The `countdown` tool in `mcp-example` shows it (`steps`, `delayMs`); try it with curl:

```
curl -N http://localhost:8080/mcp -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -H 'MCP-Protocol-Version: 2026-07-28' -H 'Mcp-Method: tools/call' -H 'Mcp-Name: countdown' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"countdown","arguments":{"steps":3},"_meta":{"progressToken":"t1","io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}'
```

## Notifications (`subscriptions/listen`)

A client opens a long-lived stream with `subscriptions/listen` and says what it wants to hear about:

```json
{"jsonrpc":"2.0","id":1,"method":"subscriptions/listen","params":{"notifications":{"toolsListChanged":true,"resourcesListChanged":true,"resourceSubscriptions":["ticket://stats"]},"_meta":{...}}}
```

The reply is an SSE stream (the request must list `text/event-stream` in `Accept`, otherwise 406). Its first event is `notifications/subscriptions/acknowledged`, which lists only what the server agreed to; after that come `notifications/tools/list_changed`, `prompts/list_changed`, `resources/list_changed` and `resources/updated` (with the `uri`) as they happen. Every notification carries `_meta["io.modelcontextprotocol/subscriptionId"]`, which is the `id` of the listen request. Closing the connection ends the subscription.

**The application says when something changed.** The registry is built from CDI beans at startup, so the server cannot see changes by itself. Inject `McpNotifications` where the change happens:

```java
@Inject McpNotifications notifications;

store.close(id);
notifications.resourceUpdated("ticket://" + id);   // clients that subscribed to exactly this URI
notifications.toolsListChanged();                  // call when the tools your clients can see really changed
```

The tickets example publishes `ticket://stats` (and `ticket://<id>`) when a ticket is created or closed; the `refresh_tools` tool in `mcp-example` only fires `toolsListChanged` so the mechanism can be tried.

**What the server promises and checks**
- It never sends a notification type the client did not ask for, and the acknowledgement lists only what it honors: list-changed for features it has, and resource URIs that exist **and** that the caller's scopes allow it to read. Anything else is silently left out.
- Several changes before the stream gets to write are one notification (list-changed is idempotent; updated URIs are de-duplicated). A client that falls more than 1000 URIs behind is closed and should reconnect and reread.
- The stream ends with a final `complete` response (and `_meta` subscription id) when the server closes it: the caller's token expires (the `exp` claim), the application stops, or the client fell behind. The client reconnects, with a fresh token if necessary.
- Idle streams get an SSE comment line every `mcp.listen.keepalive-ms`. A failed write means the client is gone, and the stream and its thread are released within one interval.

**Limits you must know about**
- **One thread per open stream.** By default that is a container request thread. It is the simplest thing that works on every server, and it is the main cost of the feature. `mcp.listen.max` (100) and `mcp.listen.max-per-caller` (10) bound it, but a stream count near your HTTP thread pool size will starve normal requests: size the pool, lower the limit, or switch on the async mode below. Over a limit, the server answers 503.
- **Per instance.** A stream lives on the instance that accepted it, so the clients of the other instances hear nothing unless the change reaches them. With a single instance there is nothing to do; with several, see "Several instances" below.
- No resumption: events published while a client is disconnected are lost; the client rereads after reconnecting. The spec removed SSE resumption in 2026-07-28 (no event ids, no `Last-Event-ID`), so this is by design.
- `server/discover` advertises `listChanged` (and `subscribe` for resources) in the capability objects. The revision's pages I could read do not define these fields, so they follow the earlier convention; clients that ignore them still work, because a listen request is answered either way.

### Several instances: the bridge

Behind a load balancer each instance holds some of the streams, and `McpNotifications` only reaches its own. The library has no messaging of its own, so it hands every change to a `McpNotificationBridge` that you write on top of what you already run (a JMS topic, database notifications, a cache event, ...). Make it a CDI bean:

```java
@ApplicationScoped
public class TopicBridge implements McpNotificationBridge {

    @Inject McpNotifications notifications;

    @Override
    public void publish(McpChange change) throws Exception {
        topic.send(change.encode());                          // a short JSON text
    }

    void onMessage(String text) {                             // your subscriber, running on every instance
        notifications.receive(McpChange.decode(text));        // tells the clients of this instance
    }
}
```

Then one call anywhere reaches every instance: `notifications.resourceUpdated("ticket://7")` tells the local clients at once and passes the change to `publish`; on the other instances `receive` tells theirs. What to know:

- **Calls still never block.** A separate thread hands the changes to the bridge, one at a time and in order, so a slow broker does not slow the code that announces a change. `publish` may block. An exception is logged and drops that one change.
- **No loops, no echo.** `receive` never publishes again, and it ignores a change that carries this instance's `instanceId()`, so a topic that delivers to its sender is fine.
- **The queue is bounded (1000 changes).** A change that is still waiting is not queued twice. If the bridge stays slow and the queue fills, further changes are dropped with a warning; they are lost for the other instances, like any change made while the transport is down. Clients that listen should read the lists again after they reconnect.
- **Registrations are not carried.** `McpRegistrations` changes the registry of its own instance, so make the same call on each instance (at startup, or from your own topic). It tells the local clients and does not publish, because telling other instances' clients before their registry changed would send them to a list that has not changed yet.
- **The bridge is looked up on the first change,** not at startup, because a bridge normally injects `McpNotifications`. Several bridges are all used. The decoder accepts at most 8192 characters and refuses anything that is not a change of this version; treat the topic as untrusted input if other systems can write to it.
- **Tested here with an in-memory topic only.** It has not been run against a real broker, and not through a container.

### Async mode: virtual threads

With `mcp.listen.async=true` a listen stream no longer holds the container's request thread. The endpoint suspends the request (`@Suspended AsyncResponse`), commits the SSE response, and runs the wait loop on a JDK virtual thread; the request thread returns to the pool at once. An idle stream then costs a parked virtual thread, so `mcp.listen.max` can be raised well beyond the HTTP pool size.

- **Only listen streams move.** A streamed `tools/call` still runs on the request thread, because tools may use request-scoped beans and the caller's security context.
- **No container context on the stream thread.** The stream is already authenticated and only waits and writes, so unmanaged virtual threads are enough. To use an executor the container manages, define one in your application and name it (the `virtual` attribute needs Jakarta Concurrency 3.1, part of EE 11):

  ```java
  @ManagedExecutorDefinition(name = "java:app/concurrent/McpListen", virtual = true)
  ```
  ```
  mcp.listen.async=true
  mcp.listen.executor=java:app/concurrent/McpListen
  ```
  If the name cannot be looked up, the server logs a warning and uses plain virtual threads.
- **JDK:** the library targets Java 21 and the waiting uses a lock and a condition, so it does not pin carrier threads on 21. On JDK 24 and later (including 25) even `synchronized` code no longer pins, which makes no difference here but helps tools you run yourself.
- **Servlet async timeout:** the endpoint switches off the container's default async timeout (typically 30 seconds) for its suspended requests. If a proxy in front of the server cuts idle connections, keep `mcp.listen.keepalive-ms` below its idle timeout.
- Off by default. Verified on Payara and WildFly (reported by the user). On Open Liberty `McpAsyncListenIT` passed; the one failure there was a test that reads the container log, because Liberty's console hides INFO lines by default (the test `server.xml` now sets `consoleLogLevel="INFO"`).

## Argument validation

Before a tool runs, its arguments are checked against the tool's `inputSchema`; a violation is a -32602 error naming the first problem (`Property 'address.zip' must be of type integer`, `Missing required property 'name'`) and the tool is not called. This applies to hand-written `McpToolHandler` schemas as much as to generated ones, so a schema is now enforced, not just advertised.

Checked: `type`, `enum`, `const`, `allOf`, `anyOf`, `oneOf`, `not`, `if`/`then`/`else`; `minimum`, `maximum`, `exclusiveMinimum`, `exclusiveMaximum`, `multipleOf`; `minLength`, `maxLength`, `pattern`; `items`, `prefixItems`, `minItems`, `maxItems`, `uniqueItems`, `contains`, `minContains`, `maxContains`, `unevaluatedItems`; `properties`, `required`, `additionalProperties`, `patternProperties`, `propertyNames`, `minProperties`, `maxProperties`, `dependentRequired`, `dependentSchemas`, `unevaluatedProperties`; and `$ref` inside the schema (`#`, `#/$defs/name`, `$anchor`). `unevaluatedProperties` and `unevaluatedItems` count what the keywords next to them and the schemas applied in place (`$ref`, `allOf`, the `anyOf`/`oneOf` branches that match, `if`/`then`/`else`, `dependentSchemas`) have evaluated. `format` is checked only with `mcp.validation.formats=true` (see the table above); a format name the library does not know is accepted. Not checked: other unknown keywords, and `$ref` to anything outside the schema (never fetched, so it is skipped; fetching schemas from the network would be a request-forgery risk). `pattern` uses `java.util.regex`, unanchored; a pattern that does not compile is skipped. Messages never repeat values the client sent. Check what a schema cannot express yourself.

**Recursive records.** A record such as `record Folder(String name, List<Folder> folders)` gets `$defs` at the root of the tool's `inputSchema` (and of its `outputSchema`) and a `{"$ref": "#/$defs/Folder"}` where it is used; records that do not refer back to themselves stay inline. Mutually recursive records each get a definition. Let the recursion end in the data with an `Optional` or a collection component: a `null` in a component the schema requires does not validate. Arguments and results are converted up to 32 levels deep; deeper input is refused with an invalid-arguments error. An `x-mcp-header` (`@McpParam(header = ...)`) inside a recursive record is refused at startup, because the specification allows it only on properties reachable through `properties` alone.

## Icons, `_meta` and rich content

**Icons and `_meta` on tools, prompts, resources and templates.** Every handler interface has two optional methods, `icons()` (a list of `Icon`) and `meta()` (a `JsonObject`), and the annotations have matching attributes:

```java
@McpTool(description = "Revenue chart",
         icons = {"https://example.com/chart.png"},
         meta = "{\"ui\":{\"resourceUri\":\"ui://revenue\"}}")
public ToolResult chart(String quarter) { ... }
```

The annotation attributes take URLs and JSON text. An icon with a MIME type, sizes or a theme (`new Icon(src, "image/png", List.of("48x48"), "dark")`) needs the handler interface. Icon sources must be `https`, `http` or `data:` URIs: `javascript:` and `file:` are refused at startup, as are `_meta` keys that break the spec's `prefix/name` rules (`Meta.validate`). Keys under the reserved `io.modelcontextprotocol/` prefix are allowed, because extensions define them, but the library gives them no meaning.

**Content blocks.** `Content` builds the blocks of a tool result or a prompt message, with optional annotations (`audience`, `priority`, `lastModified`) and `_meta`:

```java
return ToolResult.of(
        Content.text("March report"),
        Content.image(png, "image/png"),
        Content.resourceLink("report://2026-03", "March report").mimeType("application/pdf").size(48_213).build());
```

`ToolResult.structured(data, blocks...)` returns blocks plus structured content, and `new ToolResult.Complete(...).withMeta(meta)` sets the result's `_meta`. The server adds `io.modelcontextprotocol/serverInfo` to that object and its value wins over a handler's. `ResourceContent.withMeta(...)` sets the `_meta` of one piece of resource content, which is where MCP Apps put their `ui` settings.

Status: unit-tested (15 tests in `McpDescriptorsTest`, 2 in `AnnotatedToolHandlerTest`); no integration test yet, and no example application uses it. The sandbox that wrote this had no way to check the shapes against a real client.

## Elicitation

A tool, prompt or resource can ask the user for input in the middle of a request. In 2026-07-28 this is a Multi Round-Trip Request: the handler returns `ToolResult.ask(...)` (`PromptResult.ask`, `ResourceResult.ask`), the client asks the user and retries the same call with `inputResponses`, and the handler reads the answer. Nothing is kept on the server between the two calls.

```java
public ToolResult call(ToolCall call) {
    Optional<Elicitation.Answer> answer = Elicitation.answer(call.inputResponses(), "contact");
    if (answer.isEmpty()) {
        if (!call.clientSupportsElicitation(Elicitation.Mode.FORM)) {
            return ToolResult.error("Pass the email address as an argument; this client cannot ask the user.");
        }
        Elicitation.Form form = Elicitation.form("Where can we reach you?")
                .string("email", p -> p.format("email").required())
                .integer("age", p -> p.minimum(18));
        return ToolResult.ask(InputRequests.of("contact", form), null);
    }
    if (!answer.get().accepted()) {
        return ToolResult.text("No problem.");                      // declined or cancelled
    }
    return ToolResult.text("Sending to " + answer.get().string("email").orElseThrow());
}
```

- **Form mode** (`Elicitation.form`): a flat object of `string` (with `minLength`, `maxLength`, `format` of `email`, `uri`, `date`, `date-time`), `number`, `integer` (`minimum`, `maximum`), `bool`, `choice` (one of a list, optionally with display titles) and `multiChoice` (`minItems`, `maxItems`), each with `title`, `description`, `required` and a default. That is the subset the specification allows. Settings that do not fit a type throw when the form is built.
- **The user's answer is client input.** `form.problems(answer)` checks required properties, types, ranges, formats and allowed values and returns the problems; ask again (a new `ToolResult.ask`) instead of failing the call. `Elicitation.answer` returns empty for a missing or malformed answer, so the same code path covers the first attempt and a bad retry. `accepted()`, `declined()` and `cancelled()` are the three actions; anything else counts as cancelled.
- **Sensitive data.** The specification forbids asking for passwords, API keys, tokens or payment data in form mode. `Elicitation.form` throws for property names, titles, descriptions and messages that look like that (`password`, `secret`, `api key`, `access token`, `credential`, `card number`, `cvv` ...). This is a heuristic, not a guarantee, and it has no switch to turn it off. Use URL mode for such data.
- **URL mode** (`Elicitation.url(message, url)`): the user is sent to a page of yours and the data never reaches the client. The URL must be absolute `https` (or `http` for local development) and carry no user info. It must also hold no personal data and not be pre-authenticated, and your page must check that whoever opens it is the user the request was made for. The client's `accept` only means the user agreed to open the page; keep a signed `requestState` (`McpRequestStates`, see above) and check on the retry whether the work there is finished, or ask again.
- **Capabilities.** `call.clientSupportsElicitation(mode)` follows the specification: `elicitation: {}` means form only, `form` and `url` declare each mode. Ask only for modes the client declared; the library does not stop you.
- **Several requests at once:** `InputRequests.of("a", ...).add("b", ...)`. Keys must be unique and come back as the keys of `inputResponses`.

Sampling and roots requests are not part of this API: the specification deprecates both, and the conformance fixtures still build them by hand with `ToolResult.inputRequired(JsonObject, String)`. See [docs/design/deprecated-features.md](docs/design/deprecated-features.md).

Status: unit-tested (`ElicitationTest`, 12 tests). `ElicitationIT` (7 tests, fixture WAR: form, bad answer, decline, no capability, URL mode with a signed state, tampered state): in the run of 2026-11-09 09:45 six tests passed on Payara, WildFly and Open Liberty; the URL-mode test failed on all three because of a bug in the test itself (one JSON builder added under two keys, which Parsson empties), fixed since and not yet re-run. The conformance fixtures now build their elicitation requests and read their answers with this API (the conformance suite was run afterwards on Payara and all `input-required-result-*` scenarios passed). The form schema subset and the URL rules come from the 2026-07-28 elicitation page; the sensitive-word list is my own.

## Parameter headers (`x-mcp-header`)

A tool can ask clients to copy chosen arguments into request headers, so that a gateway or load balancer can route on them without parsing the body. Annotate the property in the `inputSchema`:

```json
{"type": "object", "properties": {"region": {"type": "string", "x-mcp-header": "Region"}}}
```

or, for `@McpTool` methods, `@McpParam(header = "Region") String region`. A client then sends `Mcp-Param-Region: eu-west1` next to the usual `Mcp-Method` and `Mcp-Name`. The server compares the headers with the body on every `tools/call`, before the arguments are validated, and answers HTTP 400 with `-32020` (`HeaderMismatch`) when

- the argument is present and its header is missing,
- the header differs from the argument (strings exactly, integers numerically, so `42.0` equals `42`; booleans as `true`/`false`),
- the header holds characters outside printable ASCII without the `=?base64?...?=` form, or invalid base64.

An absent or `null` argument needs no header. `Mcp-Param-*` headers the schema does not mention are ignored. Header names are case-insensitive, values are not.

The specification's limits are checked when the tool is registered, and a violation stops the application at startup (or fails `McpRegistrations`) with the tool name: the name must be a non-empty HTTP token and unique ignoring case, the property must be a `string`, `integer` or `boolean` (not `number`), and it must be reachable from the root through `properties` alone, so not under `items`, `oneOf`/`anyOf`/`allOf`, `if`/`then`/`else` or `$ref`. A repeated header reaches the server as one comma-joined value from most containers and fails the comparison.

## Skills (experimental)

The Skills extension (`io.modelcontextprotocol/skills`, SEP-2640) lets a server hand Agent Skills to clients: a directory with a `SKILL.md` and supporting files, read through the ordinary `resources/read`. The wire format was still being revised when this was written (the SEP and the published extension page differ in details), so treat this as experimental.

Provide skills as beans:

```java
@ApplicationScoped
public class MySkills {

    @Produces
    McpSkill codeReview() {
        return McpSkill.fromClasspath(MySkills.class.getClassLoader(), "code-review", "skills/code-review", "SKILL.md", "references/checklist.md");
    }
}
```

`McpSkill.of(name, Map<String, String>)` builds one from text held in memory; or implement `McpSkill` yourself (`name()`, `files()` as bytes, optional `frontmatter()` and `requiredScopes()`). A classpath cannot be listed the same way on every server, so `fromClasspath` takes the file names.

As soon as one skill exists, `server/discover` announces `resources` and `io.modelcontextprotocol/skills` with `directoryRead: true`, and the server answers

- `skills/list` (paginated, with `ttlMs`/`cacheScope`) and `skills/get`: the entry is `{uri, frontmatter, resources}`, where `resources` is the manifest of every file with its URI, SHA-256 digest and size, computed from the bytes your skill returns;
- `resources/read` for every file at `skill://<name>/<path>` (text, or base64 for files that are not UTF-8);
- `resources/directory/read` for the direct children of a skill's directories.

Everything is checked at startup, and a violation stops the application with the skill name: the name is URI-safe and equals `name` in the frontmatter, `SKILL.md` starts with YAML frontmatter with `name` and `description`, file paths have no `..`, empty or odd segments, and no two skills share a name. More than 512 files or 16 MiB per skill only logs a warning (the specification says "should not").

Limits to know about:

- The built-in frontmatter reader handles the flat subset of YAML: `key: value` with plain, quoted, boolean and integer scalars, and `>` / `|` blocks. Nested values (`metadata:`, lists) are refused with a message; override `McpSkill.frontmatter()` to give the fields as JSON. Hosts compare these fields with their own parse of the file, so the two must agree: `version: 3` is a number here, as it is in YAML, and `version: "3"` is a string.
- The digests are taken at startup; a skill whose `files()` changes later is not noticed.
- Generated skills (`"resources": "dynamic"`) are not supported.
- `skills/list` returns whole entries, never a split manifest; `requiredScopes()` hides a skill from callers without the scopes.
- Tested with unit tests and `SkillsIT` (passes on Payara, WildFly and Open Liberty) against the fixture WAR, which serves one skill from memory and one from the WAR. No host that loads skills has been tried against it.

## Tasks (experimental)

`mcp-example-tasks` is a runnable example (`build_report`, with a `demo.sh` that polls or listens).

A long-running tool call can return a task id at once instead of blocking; the client polls `tasks/get` for the status and the result. This is the `io.modelcontextprotocol/tasks` extension. It is **off by default** (`mcp.tasks.enabled=true`); the specification text for it could not be read in full when this was written, so field names follow the SEP and the overview page, and the extension may still change.

```java
@McpTool(name = "report", description = "Builds a report")
public ToolResult report(ToolCall call) {
    return ToolResult.task(ctx -> {
        ctx.status("collecting data");                       // shown by tasks/get
        JsonObject answers = ctx.ask(InputRequests.of("ok", Elicitation.form("Use last year's data?").bool("yes", p -> p.required())));   // input_required until tasks/update
        if (ctx.isCancelled()) {
            throw new RequestCancelledException("stopped", null);   // ends the task as cancelled
        }
        return ToolResult.text("done");
    });
}
```

- **Who gets a task:** the server decides per call. A client that declared the extension gets `{"resultType": "task", "taskId": ...}`; every other client gets the plain result, because the server runs the work on the request thread (`ToolCall.clientSupportsTasks()` tells the tool which). Work that calls `ctx.ask` needs the extension: inline it fails with -32021. With `mcp.tasks.enabled` off every client gets the plain result.
- **Ending:** returning a result ends the task as `completed` (also when `isError` is true); throwing `McpProtocolException` ends it as `failed` with that error; any other exception becomes an `isError` result, like a failing tool; `RequestCancelledException` ends it as `cancelled`. Cancellation is cooperative: `tasks/cancel` sets a flag the work reads with `ctx.isCancelled()`, and a task that finishes regardless stays `completed`.
- **Ownership:** a task belongs to the token subject that created it (`anonymous` when authentication is off, then the 256-bit id is the only protection). Unknown, foreign, expired and not-permitted tasks give the same -32602 "Unknown task". The tool's required scopes are checked on every `tasks/*` request. There is no `tasks/list`.
- **Where the work runs:** on `mcp.tasks.executor`, else the container's default managed executor, else JDK virtual threads. The work has no request scope: take what you need from `ctx.call()`.
- **Durable state is not durable execution.** The task *record* lives in a `TaskStore`; the *work* runs on one instance and dies with it. A running task renews a lease every `mcp.tasks.heartbeat-ms`; when the lease runs out (instance stopped or crashed), any instance that sweeps declares the task `failed` ("lost", or `cancelled` if cancellation was pending). A graceful shutdown fails the running tasks at once. Nothing is restarted unless the tool asks for it with `ToolResult.task(work).withRestartable()`: then the sweep of another instance calls the tool again with the stored call (arguments, caller, client capabilities) and runs the work from the start, at most `mcp.tasks.max-attempts` times. That is at-least-once, so the work must be safe to repeat; the stored call is run with the original caller's scopes without checking the access token again; answers to earlier input requests are dropped. `TaskContext.attempt()` tells the work which run it is. The restarted tool call runs outside a request, with a request scope started for it; the container's security context is not restored, so read the caller from `ToolCall.caller()`.
- **Stores:** the default `InMemoryTaskStore` is correct for one instance and loses everything at restart; the server logs a warning at startup. For several instances or restarts use a shared store: the `mcp-jakarta-tasks-jdbc` module, or your own `TaskStore` bean (create, find, compare-and-set `replace`, two sweep queries, delete). The server uses the one store whose `available()` is true; two available stores fail the deployment, none means the in-memory store.
- **JDBC store:** add `eu.hansolo.mcp:mcp-jakarta-tasks-jdbc` to the application and set `mcp.tasks.datasource` to the JNDI name of a `DataSource` (production), or `mcp.tasks.jdbc.url` with `mcp.tasks.jdbc.user` and `mcp.tasks.jdbc.password` (a connection per operation, no pooling: tests and small setups; the driver must be in the application). One table, `mcp_tasks` (`mcp.tasks.jdbc.table`); create it with the statements of `JdbcTaskStore.ddl(table, "TEXT")` (PostgreSQL), `"CLOB"` (H2) or `JdbcTaskStore.ddl(table, "LONGTEXT", true)` (MariaDB and MySQL, which have no `CREATE INDEX IF NOT EXISTS`, so the indexes are declared inside `CREATE TABLE`), or set `mcp.tasks.jdbc.create-schema=true` (PostgreSQL, MariaDB, MySQL and H2; the dialect follows the database's product name). Verified on PostgreSQL, MariaDB 11 and MySQL 8.4; with MySQL 8 and a connection without TLS, add `allowPublicKeyRetrieval=true` to the JDBC URL or use TLS. It uses plain standard SQL, one statement per operation, compare-and-set as `UPDATE ... WHERE id = ? AND version_no = ?`. Run the same settings on every instance, and the same database.
- **Instances:** any instance answers `tasks/get`, `tasks/update` and `tasks/cancel` from the shared store. Cancellation and answers reach the instance that runs the work within one heartbeat (cancel) or one `mcp.tasks.input-poll-ms` (input). An instance that dies leaves its tasks to be declared `failed` by another instance after `mcp.tasks.lease-ms`.
- **Notifications:** instead of polling `tasks/get`, a client can open `subscriptions/listen` with `notifications.taskIds` (it must have declared the extension, else -32021). The acknowledgement lists the ids the server honors (unknown, expired and foreign ids are left out; at most 50 per stream). The stream then sends `notifications/tasks` with the same content `tasks/get` would return at that moment (without `resultType`), first the current state and then on every change, and stops watching a task after it has sent a terminal state. Changes made on this instance are sent at once; the stream also looks in the task store every `mcp.tasks.notify-poll-ms` (default 2000), which is how a task that runs on another instance reaches it. Cost: one store lookup per interval for all tasks watched by the stream (`TaskStore.findAll`; the JDBC store reads them in chunks of 200 ids). Clients may keep polling. `notifications/progress` and `notifications/message` are never sent for a task.
- **Not done yet:** tasks for anything but `tools/call`, external job handles, restartable tasks.
- Tested with unit tests (engine with a controllable clock, wire behaviour), `JdbcTaskStoreTest` and `TwoInstancesTest` (two services over one H2 database), and these integration tests: `TasksIT` (9 tests incl. the listen stream; all three servers found their container's managed executor or fell back without error), `TasksClusterIT` (two server containers and PostgreSQL; 5 tests, including a stream on B that follows a task on A), `TasksClusterMariaDbIT` (the same on MariaDB 11), `TasksClusterMySqlIT` (the same on MySQL 8.4 with Connector/J; run on Payara) and `TasksJndiIT` (3 tests: the store through a JNDI `DataSource` that the server provides, with PostgreSQL; Payara gets it from a boot command file, WildFly from a CLI script, Open Liberty from `configDropins`). All passed on Payara, WildFly and Open Liberty in the run of 2026-11-10, except `TasksClusterMySqlIT` as noted. MCP Inspector 2.14.0 was also used by hand against `mcp-example-tasks` (see [scripts/client-check/README.md](scripts/client-check/README.md)).

## Trace context

A client can put OpenTelemetry trace context into the `_meta` of a request: `traceparent`, `tracestate` and `baggage`. These three keys are the one exception to the `prefix/name` rule for `_meta` keys. `call.trace()` on `ToolCall`, `PromptCall`, `ResourceCall` and `CompletionCall` returns a `TraceContext` (never null; `TraceContext.EMPTY` when the client sent none):

```java
@Override
public ToolResult call(ToolCall call) {
    TraceContext trace = call.trace();
    Span span = tracer.spanBuilder("report").setParent(propagator.extract(trace.toJson())).startSpan();   // your tracer
    ...
}
```

`traceId()`, `parentSpanId()` and `sampled()` read the parts of a valid `traceparent`. To forward the context on a request your handler makes itself, merge `call.trace().child().toJson()` into that request's `_meta`; `child()` keeps the trace id, flags, `tracestate` and `baggage` and picks a new span id (use your tracer's span id if you have one).

The library has no OpenTelemetry dependency and starts no spans. A value that does not follow the W3C format is dropped instead of failing the request (a `tracestate` also goes if the `traceparent` is missing or invalid). `tracestate` and `baggage` are only checked for printable ASCII, which is lenient on purpose. Outgoing notifications (progress, `list_changed`) carry no trace context: the specification asks for none, and a notification has no single request it belongs to.

Status: unit-tested (`TraceContextTest`, 7 tests, including a request through the handler). Not run against a real tracer. The key names and the format rule come from the 2026-07-28 specification (`basic`, "OpenTelemetry trace context"); the W3C `traceparent` rules are from my knowledge of that standard, not re-read.

## Extensions

MCP extensions are negotiated in capabilities: the server lists them in `server/discover` under `capabilities.extensions`, the client declares the ones it supports in the `clientCapabilities` of every request. Both are off unless you opt in.

**Announce** an extension by implementing `McpExtension` in a CDI bean:

```java
@ApplicationScoped
public class ReportsExtension implements McpExtension {
    public String id() { return "com.example/reports"; }          // vendor-prefix/extension-name
    public JsonObject settings() { return Json.createObjectBuilder().add("maxRows", 1000).build(); }
}
```

The id is checked at startup (`vendor-prefix/name`; duplicates and a `null` settings object are refused). Announcing is a promise that your server implements the extension: the library adds no behaviour of its own.

**Read the client's side** from the call object in a tool, resource or prompt:

```java
Optional<JsonObject> ui = call.clientExtension("io.modelcontextprotocol/ui");   // empty: fall back to plain content
call.requireClientExtension("com.example/reports");                              // mandatory: refuses with -32021
```

`requireClientExtension` answers HTTP 400 with error `-32021` (missing required client capability) and names the extension in `data.requiredCapabilities.extensions`. The specification page on extensions says only that a server "can reject" a request when a mandatory extension is missing and does not name an error code. I chose -32021 because it is the code the specification gives for a missing client capability. [Likely] correct, but not stated for extensions.

Status: unit-tested (`McpExtensionsTest`, 8 tests); `ExtensionsIT` (5 tests, fixture WAR) passes on Payara, WildFly and Open Liberty.

## MCP Apps

MCP Apps (extension `io.modelcontextprotocol/ui`) let a tool name an HTML page that a supporting client shows in a sandboxed frame instead of, or next to, the text result. `Apps` has the helpers, and `mcp-example-apps` is a complete small example (`show_clock`).

1. **Announce the extension:** `mcp.apps.enabled=true`, or a bean extending `McpAppsExtension` (as the example does).
2. **Bind the tool to a page:** `meta()` of the tool returns `Apps.toolMeta("ui://clock/app.html")`, optionally with visibility (`"model"`, `"app"`; both by default). With the annotation: `@McpTool(meta = "{\"ui\":{\"resourceUri\":\"ui://clock/app.html\"}}")`.
3. **Serve the page** as a resource whose URI starts with `ui://`: `Apps.html(uri, html, Apps.resourceMeta()...build())` gives content with the required MIME type `text/html;profile=mcp-app`. The resource meta declares what the page may contact (`connectDomains`, `resourceDomains`, `frameDomains`, `baseUriDomains`), browser features it asks for (`permission("camera")` ...), `domain` and `prefersBorder`. Declare nothing and clients apply their strictest policy.
4. **Keep the text fallback:** the tool must still return useful `content`. `call.clientSupportsApps()` tells you whether the client declared the extension and the MIME type, so you can adapt the text.

The library checks the wiring at startup (and when a tool is added at runtime): the tool's `ui` binding must be a `ui://` URI of a resource this server has (fixed or template), `visibility` must hold `model` and/or `app`, and every origin in `csp` must be a plain origin (`https://host[:port]`, `wss://...`, `https://*.example.com`, `data:`, `blob:`). The last check matters because clients build a Content-Security-Policy from those strings: an entry such as `https://a.com; script-src *` is refused instead of being passed on.

### The page's side

The page talks to the host over `postMessage` with JSON-RPC 2.0 (the page is the client, the host the server). This library does not touch that traffic; the example page (`clock.html`, one self-contained file) shows the whole exchange:

1. The page sends `ui/initialize` (`protocolVersion`, `appInfo`, `appCapabilities`; not `clientInfo`, which belongs to the MCP `initialize` between client and server and appears in a misleading example of the 2026-01-26 specification), applies the `hostContext` of the reply (theme, CSS variables) and then sends `ui/notifications/initialized`. The host sends nothing before that.
2. The host sends `ui/notifications/tool-result` with the `CallToolResult` of the tool call that opened the page. This is why `show_clock` returns `structuredContent` (`{"now": ...}`) next to its text: the page reads the structured part and falls back to the text.
3. The **Refresh** button sends `tools/call` for `refresh_clock` through the host. That tool is app-only (`Apps.toolMeta(uri, "app")`), so models do not see it, and a host must refuse page calls to tools that lack `app` visibility.
4. The page also handles `ui/notifications/tool-cancelled`, `ui/notifications/host-context-changed` and `ui/resource-teardown` (answered with `{}`), reports its size with `ui/notifications/size-changed`, ignores messages whose source is not `window.parent`, and writes received values with `textContent` only.

Status: unit-tested (`McpAppsTest`, 10 tests). `ExtensionsIT` passes on all three servers. The page's message flow is checked against a scripted fake host in Node (initialize, result, refresh, errors, teardown, a foreign sender, hostile text). `AppsIT` (5 tests, against `mcp-example-apps`) passes on all three servers (it first found a real bug on Payara: the extension was registered twice, fixed with `@Vetoed`). MCP Inspector 2.14.0 has displayed the example (the clock shows the time and Refresh works); other hosts are untested, and what each host's sandbox allows is unverified. The protocol facts (version string `2026-01-26`, method names) come from a documentation mirror of the Apps specification, not from the specification repository. [Likely] the default sandbox policy allows the inline script and style of a single-file page; if a host blocks them, the page needs `resourceDomains` or the host's rules need checking. The spec says the flat key `_meta["ui/resourceUri"]` is deprecated and this library does not emit it.

## Dynamic registration

Tools and prompts found at startup stay in the registry, but an application can also add, replace and remove them while it runs. Inject `McpRegistrations`:

```java
@Inject McpRegistrations registrations;

registrations.addTool(new ReportTool(report));          // McpToolHandler; throws if the name is taken or invalid
registrations.replaceTool(new ReportTool(report2));     // add, or replace the tool of the same name
registrations.removeTool("report-" + report.id());      // false if there was no such tool
registrations.addPrompt(...); registrations.replacePrompt(...); registrations.removePrompt(...);
```

Every real change sends `notifications/tools/list_changed` (or `prompts/...`) to all clients that hold a `subscriptions/listen` stream, so they list again; a removal of something that does not exist sends nothing. Handlers are checked as at startup, and one that declares required scopes is refused while `mcp.auth.enabled` is false. A call that is already running when its tool is removed finishes normally. Prompts: if the server started without any, the `prompts` capability is switched on by the first one you add and stays on afterwards.

This is per instance, like `McpNotifications`: with several instances, make the same call on each. Clients that do not listen see a change when their cached list expires (`mcp.cache-ttl-ms`). Resources, resource templates and completions are fixed at startup.

## Pagination

Set `mcp.page-size` to split the four list methods into pages; the default (0) returns each list whole and rejects every cursor. Pages are in ascending order of tool/prompt name, resource URI or template, and each result carries `nextCursor` when more items follow. The cursor is keyset-based: it encodes the last key returned (`k1:<key>`, base64url) and the next page is everything after it. So no server state is needed, any instance can serve any page, and a cursor stays valid when items are added or removed. It is not signed: it contains nothing secret, and a forged cursor only picks a starting point in a list the caller may see anyway, because scope filtering is applied on every page. Cursors longer than 4096 characters, malformed ones and non-strings are -32602. `ttlMs` and `cacheScope` are identical on all pages, as the spec requires.

## Conformance suite

`mcp-example-conformance` builds `mcp-conformance.war`, which exposes the fixtures the official suite calls (`a_conformance_ping`, `slow_compute` and the other Tasks tools, `test_simple_text`, `test_input_required_result_*`, `test://static-text`, ...). Run it:

```bash
mvn -q -DskipTests package
docker run --rm -p 8080:8080 -e MCP_TASKS_ENABLED=true -v "$PWD/mcp-example-conformance/target/mcp-conformance.war:/opt/payara/deployments/ROOT.war" payara/micro:latest
# in another terminal
npx --yes @modelcontextprotocol/conformance@alpha server --url http://localhost:8080/mcp --requirements 2026-07-28 -o results > all.txt 2>&1
```

The `docker run` line above sets `MCP_TASKS_ENABLED=true`, because the Tasks extension is off by default, and the nine `tasks-*` scenarios need it. With it, all scenarios pass on Payara, WildFly and Open Liberty (runs of 2026-11-09 and 2026-11-10). `tasks-status-notifications` reports no checks because the suite skips it itself: its harness still observes the old `tools/call` SSE response and waits for a rewrite against `subscriptions/listen`. `notifications/tasks` is covered by this project's integration tests. `mcp-example-conformance/expected-failures.yml` is empty; scenarios listed there are expected to fail, and the file can be passed with `--expected-failures` (it must then have at least one entry). The CI job `conformance` does exactly that and fails on any other failure, and also when a listed scenario starts to pass (then delete its entry). It uploads the results as an artifact.

## Not implemented yet

SSE for anything but `tools/call` progress and `subscriptions/listen` (see Streaming, Notifications); a ready-made transport for the notification bridge (you write `McpNotificationBridge` on top of your own messaging, see "Several instances"); logging (`io.modelcontextprotocol/logLevel`, `notifications/message`), left out on purpose because 2026-07-28 deprecates it and says new implementations should not adopt it; dynamic registration of resources and completions (tools and prompts can change at runtime, see Dynamic registration); skills with `"resources": "dynamic"`; remote `$ref` in argument validation (on purpose, see Argument validation); 2025-11-25 compatibility (on purpose: only 2026-07-28 is supported).

## Decisions worth a second look

- Errors about a well-formed request (unknown tool, bad arguments, bad cursor) go out as JSON-RPC errors on **HTTP 200**. Malformed metadata or headers use **400**, as the spec requires. The spec is silent on the first case, so this follows common SDK practice.
- The `Origin` default is deny-when-present, except for loopback origins (`localhost`, `127.0.0.1`, `[::1]`, any port), which the conformance suite requires and which cannot be a DNS-rebinding attacker. A "same origin" default would defeat DNS-rebinding protection, because a rebound request carries a matching `Host`.
- 401 and 403 bodies are JSON-RPC errors with the generic invalid-request code; the reserved -32020..-32099 range is only used where the spec defines a code.
- `@McpTool`, `@McpResource` and `@McpPrompt` discovery scans the CDI bean manager at startup instead of using a portable extension. It sees every enabled bean, including ones in the application archive, with no `META-INF/services` registration. The cost is a one-time scan; a bean whose type set excludes its own class (`@Typed`) is not found.
- The minimal `mcp-example` declares no scopes, because it must also start with authentication off (declared scopes fail the deployment by design). Scope enforcement per tool, resource, prompt and completion is covered by unit tests and, end to end, by `McpTicketsKeycloakIT` against the tickets example.
