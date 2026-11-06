package eu.hansolo.mcp.it;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The curl flows from the README, run against the real WAR in a real application server container (authentication off). */
class McpServerIT {

    private static GenericContainer<?> container;
    private static McpClient           client;


    @BeforeAll
    static void start() {
        Assumptions.assumeTrue(ServerFixture.dockerAvailable(), "Docker is not available: skipping integration tests");
        container = ServerFixture.create(Map.of("MCP_ALLOWED_ORIGINS", "https://allowed.example", "MCP_MAX_BODY_BYTES", "4096"), false);
        container.start();
        client = new McpClient(container.getHost(), container.getMappedPort(ServerFixture.httpPort()));
        ServerFixture.awaitDeployed(container, client);
    }

    @AfterAll
    static void stop() {
        if (container != null) {
            container.stop();
        }
    }

    // ---- discovery and listing -------------------------------------------------------------------------------------

    @Test
    void discoverAdvertisesTheStatelessVersion() throws Exception {
        McpClient.Reply reply = client.discover(null);
        assertEquals(200, reply.status());
        JsonObject result = reply.json().getJsonObject("result");
        assertEquals("complete", result.getString("resultType"));
        assertEquals("2026-07-28", result.getJsonArray("supportedVersions").getString(0));
        assertTrue(result.getJsonObject("capabilities").containsKey("tools"));
        assertEquals("public", result.getString("cacheScope"));
        assertTrue(reply.header("Content-Type").startsWith("application/json"));
    }

    @Test
    void toolsListIsSortedAndCacheable() throws Exception {
        JsonObject result = client.toolsList(null).json().getJsonObject("result");
        List<String> names = result.getJsonArray("tools").stream().map(t -> t.asJsonObject().getString("name")).toList();
        assertEquals(List.of("add", "countdown", "echo", "greet", "repeat"), names);
        assertTrue(result.getJsonNumber("ttlMs").longValue() > 0);
        assertEquals("public", result.getString("cacheScope"));
        assertEquals("complete", result.getString("resultType"));
    }

    // ---- tools -----------------------------------------------------------------------------------------------------

    @Test
    void addReturnsStructuredContent() throws Exception {
        JsonObject arguments = Json.createObjectBuilder().add("a", 2).add("b", 3).build();
        JsonObject result = client.toolsCall("add", arguments, null).json().getJsonObject("result");
        assertFalse(result.getBoolean("isError"));
        assertEquals(5, result.getJsonObject("structuredContent").getJsonNumber("sum").intValue());
    }

    @Test
    void echoReturnsTheMessage() throws Exception {
        JsonObject result = client.toolsCall("echo", Json.createObjectBuilder().add("message", "héllo 世界").build(), null).json().getJsonObject("result");
        assertEquals("héllo 世界", result.getJsonArray("content").getJsonObject(0).getString("text"));
    }

    @Test
    void repeatHonoursTheOptionalSeparator() throws Exception {
        JsonObject arguments = Json.createObjectBuilder().add("text", "ab").add("times", 3).add("separator", "-").build();
        JsonObject result = client.toolsCall("repeat", arguments, null).json().getJsonObject("result");
        assertEquals("ab-ab-ab", result.getJsonArray("content").getJsonObject(0).getString("text"));
        JsonObject withoutSeparator = client.toolsCall("repeat", Json.createObjectBuilder().add("text", "ab").add("times", 2).build(), null).json().getJsonObject("result");
        assertEquals("abab", withoutSeparator.getJsonArray("content").getJsonObject(0).getString("text"));
    }

    @Test
    void aToolExecutionExceptionIsAnErrorResultTheModelCanSee() throws Exception {
        JsonObject result = client.toolsCall("repeat", Json.createObjectBuilder().add("text", "x").add("times", 99).build(), null).json().getJsonObject("result");
        assertTrue(result.getBoolean("isError"));
        assertTrue(result.getJsonArray("content").getJsonObject(0).getString("text").contains("between 1 and 20"));
    }

    @Test
    void aFractionalIntegerArgumentIsAnInvalidParamsError() throws Exception {
        McpClient.Reply reply = client.toolsCall("repeat", Json.createObjectBuilder().add("text", "x").add("times", 1.5).build(), null);
        assertEquals(-32602, reply.json().getJsonObject("error").getInt("code"));
    }

    @Test
    void annotatedToolsAdvertiseSchemasHintsAndAnOutputSchema() throws Exception {
        JsonObject result = client.toolsList(null).json().getJsonObject("result");
        JsonObject add = result.getJsonArray("tools").stream().map(t -> t.asJsonObject()).filter(t -> t.getString("name").equals("add")).findFirst().orElseThrow();
        assertEquals("number", add.getJsonObject("inputSchema").getJsonObject("properties").getJsonObject("a").getString("type"));
        assertEquals("First addend", add.getJsonObject("inputSchema").getJsonObject("properties").getJsonObject("a").getString("description"));
        assertTrue(add.getJsonObject("annotations").getBoolean("readOnlyHint"));
        assertEquals("number", add.getJsonObject("outputSchema").getJsonObject("properties").getJsonObject("sum").getString("type"));
    }

    @Test
    void invalidArgumentsAreAnInvalidParamsError() throws Exception {
        McpClient.Reply reply = client.toolsCall("add", Json.createObjectBuilder().add("a", "x").add("b", 1).build(), null);
        assertEquals(200, reply.status());
        assertEquals(-32602, reply.json().getJsonObject("error").getInt("code"));
    }

    @Test
    void unknownToolIsAnInvalidParamsError() throws Exception {
        McpClient.Reply reply = client.toolsCall("nope", null, null);
        assertEquals(-32602, reply.json().getJsonObject("error").getInt("code"));
    }

    @Test
    void multiRoundTripWorksAcrossTwoIndependentRequests() throws Exception {
        JsonObject elicitation = Json.createObjectBuilder().add("elicitation", Json.createObjectBuilder()).build();
        McpClient.Reply first = client.rpc("tools/call", 1, Json.createObjectBuilder().add("name", "greet"), "greet", null, elicitation);
        JsonObject firstResult = first.json().getJsonObject("result");
        assertEquals("input_required", firstResult.getString("resultType"));
        assertEquals("elicitation/create", firstResult.getJsonObject("inputRequests").getJsonObject("name").getString("method"));

        JsonObject answers = Json.createObjectBuilder().add("name", Json.createObjectBuilder().add("action", "accept").add("content", Json.createObjectBuilder().add("name", "Ada"))).build();
        McpClient.Reply second = client.rpc("tools/call", 2, Json.createObjectBuilder().add("name", "greet").add("inputResponses", answers), "greet", null, elicitation);
        JsonObject secondResult = second.json().getJsonObject("result");
        assertEquals("complete", secondResult.getString("resultType"));
        assertEquals("Hello, Ada!", secondResult.getJsonArray("content").getJsonObject(0).getString("text"));
    }

    @Test
    void greetWithoutTheElicitationCapabilityIsRefused() throws Exception {
        McpClient.Reply reply = client.toolsCall("greet", null, null);
        assertEquals(400, reply.status());
        JsonObject error = reply.json().getJsonObject("error");
        assertEquals(-32021, error.getInt("code"));
        assertTrue(error.getJsonObject("data").getJsonObject("requiredCapabilities").containsKey("elicitation"));
    }

    // ---- transport rules -------------------------------------------------------------------------------------------

    @Test
    void aMissingVersionHeaderIsAHeaderMismatch() throws Exception {
        Map<String, String> headers = McpClient.standardHeaders("tools/list", null);
        headers.remove("MCP-Protocol-Version");
        McpClient.Reply reply = client.post(headers, requestBody("tools/list", "2026-07-28"));
        assertEquals(400, reply.status());
        assertEquals(-32020, reply.json().getJsonObject("error").getInt("code"));
    }

    @Test
    void anUnsupportedVersionListsTheSupportedOnes() throws Exception {
        Map<String, String> headers = McpClient.standardHeaders("tools/list", null);
        headers.put("MCP-Protocol-Version", "1900-01-01");
        McpClient.Reply reply = client.post(headers, requestBody("tools/list", "1900-01-01"));
        assertEquals(400, reply.status());
        JsonObject error = reply.json().getJsonObject("error");
        assertEquals(-32022, error.getInt("code"));
        assertEquals("2026-07-28", error.getJsonObject("data").getJsonArray("supported").getString(0));
    }

    @Test
    void aLegacyInitializeRequestIsRejectedWithAHint() throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json, text/event-stream");
        McpClient.Reply reply = client.post(headers, "{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},\"clientInfo\":{\"name\":\"old\",\"version\":\"1\"}}}");
        assertEquals(400, reply.status());
        assertTrue(reply.json().getJsonObject("error").getString("message").contains("2026-07-28"));
    }

    @Test
    void getAndDeleteAreNotAllowed() throws Exception {
        assertEquals(405, client.get("/mcp", Map.of("Accept", "text/event-stream")).status());
        assertEquals(405, client.delete(Map.of()).status());
    }

    @Test
    void aStaleSessionHeaderIsIgnoredAndNeverEchoed() throws Exception {
        Map<String, String> headers = McpClient.standardHeaders("server/discover", null);
        headers.put("Mcp-Session-Id", "stale-session-123");
        McpClient.Reply reply = client.post(headers, requestBody("server/discover", "2026-07-28"));
        assertEquals(200, reply.status());
        assertNull(reply.header("Mcp-Session-Id"));
        assertFalse(reply.body().contains("stale-session-123"));
    }

    @Test
    void unknownMethodsAre404() throws Exception {
        Map<String, String> headers = McpClient.standardHeaders("ping", null);
        McpClient.Reply reply = client.post(headers, requestBody("ping", "2026-07-28"));
        assertEquals(404, reply.status());
        assertEquals(-32601, reply.json().getJsonObject("error").getInt("code"));
    }

    @Test
    void notificationsGet202WithoutABody() throws Exception {
        McpClient.Reply reply = client.post(McpClient.standardHeaders("notifications/initialized", null), "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        assertEquals(202, reply.status());
        assertTrue(reply.body() == null || reply.body().isEmpty());
    }

    @Test
    void oversizedBodiesAreRejected() throws Exception {
        String padding = "x".repeat(8000);
        McpClient.Reply reply = client.post(McpClient.standardHeaders("server/discover", null), "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"server/discover\",\"pad\":\"" + padding + "\"}");
        assertEquals(413, reply.status());
    }

    @Test
    void foreignBrowserOriginsAreRejectedButAllowListedOnesPass() throws Exception {
        Map<String, String> headers = McpClient.standardHeaders("server/discover", null);
        headers.put("Origin", "https://evil.example");
        assertEquals(403, client.post(headers, requestBody("server/discover", "2026-07-28")).status());

        headers.put("Origin", "https://allowed.example");
        assertEquals(200, client.post(headers, requestBody("server/discover", "2026-07-28")).status());
    }

    @Test
    void theProtectedResourceMetadataEndpointIsAbsentWhenAuthenticationIsOff() throws Exception {
        assertEquals(404, client.get("/.well-known/oauth-protected-resource", Map.of()).status());
    }

    // ---- resources and prompts -------------------------------------------------------------------------------------

    @Test
    void discoverAdvertisesResourcesAndPrompts() throws Exception {
        JsonObject capabilities = client.discover(null).json().getJsonObject("result").getJsonObject("capabilities");
        assertTrue(capabilities.containsKey("resources"));
        assertTrue(capabilities.containsKey("prompts"));
    }

    @Test
    void resourcesListContainsTheAnnotatedServerInfo() throws Exception {
        JsonObject result = client.rpc("resources/list", 1, Json.createObjectBuilder(), null, null, Json.createObjectBuilder().build()).json().getJsonObject("result");
        assertEquals(List.of("config://server/info"), result.getJsonArray("resources").stream().map(r -> r.asJsonObject().getString("uri")).toList());
        assertEquals("application/json", result.getJsonArray("resources").getJsonObject(0).getString("mimeType"));
        assertEquals("public", result.getString("cacheScope"));
    }

    @Test
    void resourceTemplatesListContainsTheGreetingTemplate() throws Exception {
        JsonObject result = client.rpc("resources/templates/list", 1, Json.createObjectBuilder(), null, null, Json.createObjectBuilder().build()).json().getJsonObject("result");
        assertEquals("greeting://{name}", result.getJsonArray("resourceTemplates").getJsonObject(0).getString("uriTemplate"));
    }

    @Test
    void readingAFixedResourceReturnsJsonText() throws Exception {
        JsonObject result = readResource("config://server/info").json().getJsonObject("result");
        JsonObject content = result.getJsonArray("contents").getJsonObject(0);
        assertEquals("config://server/info", content.getString("uri"));
        assertEquals("jakarta-mcp-example", Json.createReader(new java.io.StringReader(content.getString("text"))).readObject().getString("name"));
        assertEquals(0, result.getJsonNumber("ttlMs").longValue());
    }

    @Test
    void readingATemplatedResourceDecodesTheVariable() throws Exception {
        JsonObject result = readResource("greeting://Ada%20Lovelace").json().getJsonObject("result");
        assertEquals("Hello, Ada Lovelace!", result.getJsonArray("contents").getJsonObject(0).getString("text"));
    }

    @Test
    void anUnknownResourceIsInvalidParamsWithTheUri() throws Exception {
        McpClient.Reply reply = readResource("config://nope");
        JsonObject error = reply.json().getJsonObject("error");
        assertEquals(-32602, error.getInt("code"));
        assertEquals("config://nope", error.getJsonObject("data").getString("uri"));
    }

    @Test
    void promptsListAndGetWork() throws Exception {
        JsonObject list = client.rpc("prompts/list", 1, Json.createObjectBuilder(), null, null, Json.createObjectBuilder().build()).json().getJsonObject("result");
        JsonObject summarize = list.getJsonArray("prompts").getJsonObject(0);
        assertEquals("summarize", summarize.getString("name"));
        assertTrue(summarize.getJsonArray("arguments").getJsonObject(0).getBoolean("required"));
        assertFalse(summarize.getJsonArray("arguments").getJsonObject(1).getBoolean("required"));

        JsonObject arguments = Json.createObjectBuilder().add("text", "Payara is fast").add("style", "one sentence").build();
        JsonObject result = client.rpc("prompts/get", 1, Json.createObjectBuilder().add("name", "summarize").add("arguments", arguments), "summarize", null, Json.createObjectBuilder().build()).json().getJsonObject("result");
        assertEquals("Please summarize the following text as one sentence:\n\nPayara is fast", result.getJsonArray("messages").getJsonObject(0).getJsonObject("content").getString("text"));
    }

    @Test
    void aPromptWithoutItsRequiredArgumentIsInvalidParams() throws Exception {
        JsonObject error = client.rpc("prompts/get", 1, Json.createObjectBuilder().add("name", "summarize"), "summarize", null, Json.createObjectBuilder().build()).json().getJsonObject("error");
        assertEquals(-32602, error.getInt("code"));
    }

    @Test
    void discoverAdvertisesCompletions() throws Exception {
        assertTrue(client.discover(null).json().getJsonObject("result").getJsonObject("capabilities").containsKey("completions"));
    }

    @Test
    void completionSuggestsPromptArgumentValues() throws Exception {
        JsonObject params = Json.createObjectBuilder().add("ref", Json.createObjectBuilder().add("type", "ref/prompt").add("name", "summarize")).add("argument", Json.createObjectBuilder().add("name", "style").add("value", "b").build()).build();
        JsonObject completion = client.rpc("completion/complete", 1, Json.createObjectBuilder(params), null, null, Json.createObjectBuilder().build()).json().getJsonObject("result").getJsonObject("completion");
        assertEquals(List.of("bullet points"), completion.getJsonArray("values").stream().map(v -> ((jakarta.json.JsonString) v).getString()).toList());
        assertEquals(1, completion.getInt("total"));
        assertFalse(completion.getBoolean("hasMore"));
    }

    @Test
    void completionSuggestsTemplateVariableValues() throws Exception {
        JsonObject params = Json.createObjectBuilder().add("ref", Json.createObjectBuilder().add("type", "ref/resource").add("uri", "greeting://{name}")).add("argument", Json.createObjectBuilder().add("name", "name").add("value", "a").build()).build();
        JsonObject completion = client.rpc("completion/complete", 1, Json.createObjectBuilder(params), null, null, Json.createObjectBuilder().build()).json().getJsonObject("result").getJsonObject("completion");
        assertEquals(List.of("Ada", "Alan"), completion.getJsonArray("values").stream().map(v -> ((jakarta.json.JsonString) v).getString()).toList());
    }

    @Test
    void completionForAnUnknownPromptIsInvalidParams() throws Exception {
        JsonObject params = Json.createObjectBuilder().add("ref", Json.createObjectBuilder().add("type", "ref/prompt").add("name", "nope")).add("argument", Json.createObjectBuilder().add("name", "x").add("value", "").build()).build();
        assertEquals(-32602, client.rpc("completion/complete", 1, Json.createObjectBuilder(params), null, null, Json.createObjectBuilder().build()).json().getJsonObject("error").getInt("code"));
    }

    private static McpClient.Reply readResource(String uri) throws Exception {
        return client.rpc("resources/read", 1, Json.createObjectBuilder().add("uri", uri), uri, null, Json.createObjectBuilder().build());
    }

    private static String requestBody(String method, String version) {
        JsonObject meta = Json.createObjectBuilder().add("io.modelcontextprotocol/protocolVersion", version).add("io.modelcontextprotocol/clientCapabilities", Json.createObjectBuilder()).build();
        return Json.createObjectBuilder().add("jsonrpc", "2.0").add("id", 1).add("method", method).add("params", Json.createObjectBuilder().add("_meta", meta)).build().toString();
    }

    // ---- streamed responses ----------------------------------------------------------------------------------------

    private static final String BOTH = "application/json, text/event-stream";

    @Test
    void progressIsStreamedWhileTheToolIsStillRunning() throws Exception {
        JsonObject arguments = Json.createObjectBuilder().add("steps", 4).add("delayMs", 400).build();
        try (McpClient.Events events = client.openToolCall("countdown", arguments, "tok", BOTH)) {
            assertEquals(200, events.status());
            assertTrue(events.contentType().startsWith("text/event-stream"), events.contentType());
            List<McpClient.Event> all = events.all();
            assertEquals(5, all.size());
            for (int i = 0; i < 4; i++) {
                JsonObject notification = all.get(i).json();
                assertEquals("notifications/progress", notification.getString("method"));
                assertEquals("tok", notification.getJsonObject("params").getString("progressToken"));
                assertEquals(i + 1, notification.getJsonObject("params").getJsonNumber("progress").intValue());
                assertEquals(4, notification.getJsonObject("params").getJsonNumber("total").intValue());
            }
            JsonObject last = all.get(4).json();
            assertEquals(1, last.getInt("id"));
            assertEquals("Counted down 4 steps", last.getJsonObject("result").getJsonArray("content").getJsonObject(0).getString("text"));
            // The first progress event must arrive long before the last one. If the container (or a filter) buffered the response,
            // everything would arrive together at the end and this gap would be near zero.
            long gap = all.get(4).arrivedMs() - all.get(0).arrivedMs();
            assertTrue(gap >= 900, "events arrived together (gap " + gap + " ms): the response was buffered, not streamed");
        }
    }

    @Test
    void withoutAProgressTokenOrEventStreamTheReplyIsPlainJson() throws Exception {
        JsonObject arguments = Json.createObjectBuilder().add("steps", 2).add("delayMs", 0).build();
        try (McpClient.Events events = client.openToolCall("countdown", arguments, null, BOTH)) {
            assertEquals(200, events.status());
            assertTrue(events.contentType().startsWith("application/json"), events.contentType());
        }
        try (McpClient.Events events = client.openToolCall("countdown", arguments, "tok", "application/json")) {
            assertEquals(200, events.status());
            assertTrue(events.contentType().startsWith("application/json"), events.contentType());
        }
        JsonObject result = client.toolsCall("countdown", arguments, null).json().getJsonObject("result");
        assertEquals("Counted down 2 steps", result.getJsonArray("content").getJsonObject(0).getString("text"));
    }

    @Test
    void errorsBeforeTheToolRunsAreNotStreamed() throws Exception {
        try (McpClient.Events events = client.openToolCall("nope", Json.createObjectBuilder().build(), "tok", BOTH)) {
            assertEquals(200, events.status());
            assertTrue(events.contentType().startsWith("application/json"), events.contentType());
        }
        JsonObject tooMany = Json.createObjectBuilder().add("steps", 99).build();
        try (McpClient.Events events = client.openToolCall("countdown", tooMany, "tok", BOTH)) {
            // the tool itself rejects 99 steps: that is a tool error result, delivered as the single and final event
            assertTrue(events.contentType().startsWith("text/event-stream"), events.contentType());
            List<McpClient.Event> all = events.all();
            assertEquals(1, all.size());
            assertTrue(all.get(0).json().getJsonObject("result").getBoolean("isError"));
        }
    }

    @Test
    void closingTheStreamCancelsTheRequest() throws Exception {
        JsonObject arguments = Json.createObjectBuilder().add("steps", 10).add("delayMs", 300).build();
        client.callAndDropAfterFirstEvent("countdown", arguments, "cancel-me");
        // the server notices on its next write and logs it from inside the tool; the tool takes 3 s if nothing stops it
        long deadline = System.currentTimeMillis() + 15_000;
        while (!container.getLogs().contains("countdown cancelled at step") && System.currentTimeMillis() < deadline) {
            Thread.sleep(250);
        }
        String logs = container.getLogs();
        assertTrue(logs.contains("countdown cancelled at step"), "the tool was not told that the client had gone. Ran to the end: " + logs.contains("countdown finished") + "\n--- log tail ---\n" + logs.substring(Math.max(0, logs.length() - 3000)));
        assertEquals(200, client.discover(null).status(), "the server must stay healthy after a cancelled stream");
    }
}
