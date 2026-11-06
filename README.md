# Jakarta MCP Server

A stateless [Model Context Protocol](https://modelcontextprotocol.io) server for **Jakarta EE 11** (Payara 7), written for **JDK 25** and compiled to **Java 21** bytecode, so it runs on servers with JDK 21 or newer. It speaks spec revision **2026-07-28**: no `initialize` handshake, no `Mcp-Session-Id`, every request self-describing, so any request can land on any instance behind a round-robin load balancer.

The only dependency is the Jakarta EE 11 Web Profile API (CDI, JAX-RS, JSON-P). No Quarkus, no Helidon, no MicroProfile, no third-party MCP SDK, no JWT library (token validation uses the JDK's own crypto).

[![CI](https://github.com/HanSolo/jakarta-mcp-server/actions/workflows/ci.yml/badge.svg)](https://github.com/HanSolo/jakarta-mcp-server/actions/workflows/ci.yml) · [Issues](https://github.com/HanSolo/jakarta-mcp-server/issues) · License: Apache-2.0

## Status: read this first

- **What has been run:** `mvn verify` of the original server (discover, tools/list, tools/call, MRTR) passed on the author's machine, and the server was exercised with MCP Inspector (`--protocol-era modern`) and curl against Payara Micro.
- **Verified on the author's machine (macOS, Docker Desktop 29, Payara Micro 7.2026.10 Build 18 via `payara/micro:latest`, Keycloak 26.4):** `mvn clean verify` passed with 145 unit tests and 46 integration tests (30 in `McpServerIT` at the time, 34 now with the completion tests, 9 in `McpAuthServerIT` with a test issuer, 7 in `McpKeycloakIT` with a real Keycloak). That covers CDI scanning of `@McpTool` / `@McpResource` / `@McpPrompt` beans, the well-known metadata endpoint, 401/403 challenges, audience binding, MRTR and the `eu.hansolo.mcp` rename.
- **Also passed on WildFly 42.0.0.Final and Open Liberty** (`-Dmcp.server=wildfly` / `liberty`; reported by the user, logs not shared with the author).
- **Tickets example:** `mvn clean verify` passes on Payara with Keycloak (178 unit tests at that time, 8 tickets integration tests). Passes on WildFly (reported by the user). **Open Liberty:** the tickets example does not start there without a data source: Liberty creates a container-managed `EntityManagerFactory` for `@PersistenceUnit` and looks for `java:comp/DefaultDataSource` instead of using the `jakarta.persistence.jdbc.*` properties of the example's resource-local unit. The tickets integration test is therefore skipped on Liberty. A `<dataSource id="DefaultDataSource">` in `server.xml` with the H2 jar on the server should fix it (untested).
- **Streaming (progress notifications over SSE):** passes on Payara (`mvn clean verify`, 4 streaming integration tests, including one that proves events arrive while the tool is still running and one that resets the connection and checks that the tool is told). The first version, a JAX-RS `StreamingOutput`, was buffered by Jersey; the servlet-based version fixed that. Not yet run on WildFly or Open Liberty.
- **Notifications (`subscriptions/listen`, list-changed, resource updates):** passes on Payara (13 unit tests, 3 integration tests in `McpServerIT`, 2 in `McpTicketsKeycloakIT`). Not yet run on WildFly or Open Liberty.
- **Async listen mode (`mcp.listen.async=true`, virtual threads):** passes on Payara (`mvn clean verify`: 196 library unit tests and 70 integration tests, including `McpAsyncListenIT`: the acknowledgement and notification, 60 open streams while ordinary requests are still answered, and a stream that lives past 40 seconds). Every request now goes through a suspended JAX-RS response, which the whole suite exercised. Not yet run on WildFly or Open Liberty.
- **Not yet run:** the Docker Compose setup, the CI workflow and the `release` profile. After renaming packages or moving files, always build with `mvn clean verify`: stale classes in `target/` end up in the WAR and the deployment fails with a `ClassNotFoundException`.
- **Unverified:** behaviour with a real identity provider (see the Keycloak item above; any other provider is untested). `latest` is a moving tag: pin the image with `-Dmcp.image=payara/micro:<tag>` once you have checked the tag name on Docker Hub.
- **2026-07-28 clients only.** Clients that still require the `initialize` handshake (2025-11-25 and earlier) get HTTP 400 naming the supported version.
- **Authentication is off by default** (see below). With it off, anyone who can reach the endpoint can call every tool, and the server logs a warning at startup.

## Layout

| Module | Contents |
| --- | --- |
| `mcp-jakarta` | Library JAR: tool, resource and prompt APIs (`@McpTool`, `@McpResource`, `@McpPrompt` and the matching handler interfaces), protocol handling (`McpRequestHandler`, container-independent), OAuth resource-server code (`auth`), JAX-RS resources, CDI wiring |
| `mcp-example` | WAR deployed at the context root: annotated tools (`echo`, `add`, `repeat`), a handler-style tool (`greet`, multi round-trip), a resource (`config://server/info`), a template (`greeting://{name}`) and a prompt (`summarize`) |
| `mcp-example-tickets` | A realistic example: a support-ticket service with a database, scopes and a confirmation step (see [The tickets example](#the-tickets-example)). Needs authentication |
| `mcp-example-conformance` | A fixture WAR with the tools, prompts and resources the official conformance suite expects (see [Conformance suite](#conformance-suite)). A test aid, not an example to copy |
| `mcp-it` | Integration tests: starts the example WAR in a Payara Micro, WildFly or Open Liberty container with Testcontainers |

## Supported servers

| Server | Status | How the integration tests run it |
| --- | --- | --- |
| Payara 7 (Micro) | **Verified** by the author (7.2026.10 Build 18) | `-Dmcp.server=payara` (default), image `payara/micro:latest` |
| WildFly | **Verified** on `quay.io/wildfly/wildfly:latest-jdk21` (WildFly 42.0.0.Final, RESTEasy 7.0.5). The first attempt failed with `UnsupportedClassVersionError` because the WAR was compiled for Java 25; the default is now Java 21 bytecode and the suite passes | `-Dmcp.server=wildfly`, image `quay.io/wildfly/wildfly:latest-jdk21` |
| Open Liberty | **Verified** (user-reported; log not shared with the author). The first attempt failed only because the image tag I had guessed did not exist. `mcp-it/src/test/resources/liberty/server.xml` enables `jakartaee-11.0` | `-Dmcp.server=liberty`, image `icr.io/appcafe/open-liberty:full-java21-openj9-ubi-minimal` |

The code uses only Jakarta EE 11 Web Profile APIs and has now run on three servers (Payara, WildFly, Open Liberty). Open Liberty also ships its own MCP support (`mcp-1.0`); use this library when you need portability across servers or the 2026-07-28 stateless protocol. The image tags above are defaults chosen from the vendors' naming schemes; if one does not exist, override it with `-Dmcp.image=...`. If you run these and something breaks, the failing assertion plus the container log (printed on startup failure) is the useful bug report. CI (`.github/workflows/ci.yml`) runs all three; All three are required to pass.

## Adding the library to your own application

1. Depend on the library (Jakarta EE 11 APIs stay `provided`; the server supplies JSON-P):

   ```xml
   <dependency>
       <groupId>eu.hansolo.mcp</groupId>
       <artifactId>mcp-jakarta</artifactId>
       <version>0.3.0</version>
   </dependency>
   ```

   `0.3.0` is on Maven Central. To use the current development state instead, run `mvn install` in this project and depend on `0.3.0-SNAPSHOT`. See [CHANGELOG.md](CHANGELOG.md) for what changed since 0.1.0.
2. Register the endpoint classes in your JAX-RS `Application` (see `ExampleApplication`): `McpResource` and, if you use authentication, `ProtectedResourceMetadataResource`. The endpoint is `<context root>/mcp`. OAuth metadata discovery (RFC 9728) needs the application at the host root.
3. Add `@McpTool`, `@McpResource` and `@McpPrompt` methods to your CDI beans (any bean-defining scope; with `bean-discovery-mode="annotated"` the class needs a scope annotation).
4. Configure through system properties or environment variables (see the table below). Authentication is off by default.

## Build and run

```bash
mvn verify                                  # JDK 21 or newer; unit tests, and integration tests if Docker is available
mvn verify -Dmaven.compiler.release=25      # only if every server you deploy to runs JDK 25
mvn verify -Dmcp.image=payara/micro:<tag>   # pin the image (-Dpayara.image still works for Payara)
mvn verify -Dmcp.server=wildfly              # or liberty; see "Supported servers"

java -jar payara-micro-7.x.jar --deploy mcp-example/target/mcp-example.war
```

Integration tests (`*IT`) run in the `verify` phase through Failsafe and are **skipped, not failed, when Docker is unavailable**.

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
| Parameters | `String`, booleans, integer and floating types and wrappers, `BigInteger`, `BigDecimal`, enums, `List`/`Set`/`Collection`/arrays, `Map<String,V>`, records (nested objects), `JsonObject`/`JsonArray`/`JsonValue`, `Optional<T>` for optional arguments |
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

The server can act as an OAuth 2.1 **resource server** as the MCP authorization spec describes: it validates bearer access tokens and tells clients where to get them. It is not an authorization server; you need one (Keycloak, Entra ID, Auth0, ...) that issues JWT access tokens and publishes a JWKS.

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

Notes: `tools/list`, `resources/list`, `resources/templates/list` and `prompts/list` show a caller only what their scopes allow. When any primitive declares scopes (and authentication is on), list results use `cacheScope` `private` so shared caches cannot hand one caller's list to another; otherwise `public`. Reading or getting something you lack the scope for is a 403 `insufficient_scope` challenge. Scopes on resources and prompts follow the same rule as for tools: declaring them with authentication off fails the deployment. Opaque tokens (introspection, RFC 7662) are not built in; provide your own `McpAuthenticator` bean (`@Alternative @Priority`). Dynamic client registration and the authorization-server side are out of scope. Tokens are not revocable between requests beyond their expiry, so keep access-token lifetimes short.

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

Not covered: the browser-based authorization-code flow and dynamic client registration (RFC 7591), which MCP clients such as Inspector use to register themselves. Keycloak restricts anonymous registration by policy; whether a given client completes the flow against this server has not been tested.

## Releasing to Maven Central

For maintainers. Uses the Central Portal; the namespace `eu.hansolo` must be verified for your account.

1. A server `central` in `~/.m2/settings.xml` holding a portal user token, and a GPG key whose public half is on a keyserver.
2. Set a release version (`mvn versions:set -DnewVersion=0.3.0 -DgenerateBackupPoms=false`), commit, tag.
3. `mvn -Prelease clean deploy -pl .,mcp-jakarta` signs and uploads the parent POM and `mcp-jakarta` (jar, sources, Javadoc). The `-pl` matters: the plugin bundles every module that is part of the build, so without it the example WARs and `mcp-it` would be uploaded too.
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
| `mcp.page-size` | 0 | Maximum items per page for `tools/list`, `resources/list`, `resources/templates/list` and `prompts/list`; 0 returns each list whole |
| `mcp.listen.max` | 100 | Most `subscriptions/listen` streams open at once on this instance; each holds one thread (a container thread unless `mcp.listen.async` is on). 0 switches subscriptions off |
| `mcp.listen.max-per-caller` | 10 | Most streams one caller (token subject) may hold open |
| `mcp.listen.async` | false | Run `subscriptions/listen` streams on virtual threads, so they do not hold container request threads (see Notifications) |
| `mcp.listen.executor` | none | JNDI name of a managed executor to run those streams on instead of JDK virtual threads |
| `mcp.request-state.secret` | random per instance | HMAC key for `McpRequestStates` (see Writing tools). Set the same value on every node of a cluster, or a state issued by one node is refused by another |
| `mcp.request-state.ttl-ms` | 600000 | How long a signed request state stays valid |
| `mcp.listen.keepalive-ms` | 15000 | How often an idle stream gets a comment line; this is also how a vanished client is noticed and its thread released |
| `mcp.server.name`, `mcp.server.version` | from build | Identity returned in `_meta` and discovery |
| `mcp.instructions` | none | Guidance for the model, returned by `server/discover` |
| `mcp.auth.enabled` | false | Turn on bearer-token authentication |
| `mcp.auth.resource` | required if enabled | Canonical URL of the MCP endpoint; default audience; basis of the metadata URL |
| `mcp.auth.issuer` | required if enabled | Expected `iss` |
| `mcp.auth.jwks-uri` | required if enabled | Where signing keys are published (https) |
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
- **Closing the connection cancels the request.** The server notices on its next write: `call.progress(...)` then throws `RequestCancelledException`, which a tool should let propagate. A tool that never reports progress cannot be cancelled this way, because nothing is written. The server sends nothing more for a cancelled request.
- **Progress must increase** with every report of one call (the spec requires it); a repeated or smaller value throws `IllegalArgumentException`, which counts as a tool failure.
- **Only `tools/call`.** Other methods are always plain JSON. There is no resumption (`Last-Event-ID` is ignored, as the spec says). Long-lived notification streams are `subscriptions/listen` (see Notifications).
- **The tool runs on the request thread** while the response is open, so a long call holds a container thread. Keep the call as short as the work allows; there is no keep-alive comment, so very long silences may be cut by proxies with idle timeouts.
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
- **Per instance.** A stream lives on the instance that accepted it, so a change must be published on every instance (a message topic, database notifications, ...) and each instance calls `McpNotifications`. With a single instance there is nothing to do.
- No resumption: events published while a client is disconnected are lost; the client rereads after reconnecting.
- `server/discover` advertises `listChanged` (and `subscribe` for resources) in the capability objects. The revision's pages I could read do not define these fields, so they follow the earlier convention; clients that ignore them still work, because a listen request is answered either way.

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

## Pagination

Set `mcp.page-size` to split the four list methods into pages; the default (0) returns each list whole and rejects every cursor. Pages are in ascending order of tool/prompt name, resource URI or template, and each result carries `nextCursor` when more items follow. The cursor is keyset-based: it encodes the last key returned (`k1:<key>`, base64url) and the next page is everything after it. So no server state is needed, any instance can serve any page, and a cursor stays valid when items are added or removed. It is not signed: it contains nothing secret, and a forged cursor only picks a starting point in a list the caller may see anyway, because scope filtering is applied on every page. Cursors longer than 4096 characters, malformed ones and non-strings are -32602. `ttlMs` and `cacheScope` are identical on all pages, as the spec requires.

## Conformance suite

`mcp-example-conformance` builds `mcp-conformance.war`, which exposes the fixtures the official suite calls (`test_simple_text`, `test_input_required_result_*`, `test://static-text`, ...). Run it:

```bash
mvn -q -DskipTests package
docker run --rm -p 8080:8080 -v "$PWD/mcp-example-conformance/target/mcp-conformance.war:/opt/payara/deployments/ROOT.war" payara/micro:latest
# in another terminal
npx --yes @modelcontextprotocol/conformance@alpha server --url http://localhost:8080/mcp --requirements 2026-07-28 -o results > all.txt 2>&1
```

Scenarios for features this server does not implement are expected to fail: the `tasks-*` scenarios (Tasks extension, not scored) and the pending `x-mcp-header` scenarios. The CI job `conformance` runs the suite and uploads the results; it does not block merges.

## Not implemented yet

SSE for anything but `tools/call` progress and `subscriptions/listen` (see Streaming, Notifications); dynamic registration of tools at runtime; the Tasks extension; `x-mcp-header` / `Mcp-Param-*` headers; opaque-token introspection; full JSON Schema 2020-12 validation of arguments (the handler checks `required`, top-level `type` and `additionalProperties: false`; `@McpTool` methods additionally get full type conversion); recursive record types in `@McpTool` signatures (no `$ref` support); 2025-11-25 compatibility.

## Decisions worth a second look

- Errors about a well-formed request (unknown tool, bad arguments, bad cursor) go out as JSON-RPC errors on **HTTP 200**. Malformed metadata or headers use **400**, as the spec requires. The spec is silent on the first case, so this follows common SDK practice.
- The `Origin` default is deny-when-present, except for loopback origins (`localhost`, `127.0.0.1`, `[::1]`, any port), which the conformance suite requires and which cannot be a DNS-rebinding attacker. A "same origin" default would defeat DNS-rebinding protection, because a rebound request carries a matching `Host`.
- 401 and 403 bodies are JSON-RPC errors with the generic invalid-request code; the reserved -32020..-32099 range is only used where the spec defines a code.
- `@McpTool`, `@McpResource` and `@McpPrompt` discovery scans the CDI bean manager at startup instead of using a portable extension. It sees every enabled bean, including ones in the application archive, with no `META-INF/services` registration. The cost is a one-time scan; a bean whose type set excludes its own class (`@Typed`) is not found.
- The minimal `mcp-example` declares no scopes, because it must also start with authentication off (declared scopes fail the deployment by design). Scope enforcement per tool, resource, prompt and completion is covered by unit tests and, end to end, by `McpTicketsKeycloakIT` against the tickets example.
