package eu.hansolo.mcp.server;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import eu.hansolo.mcp.api.Caller;
import eu.hansolo.mcp.api.McpPromptHandler;
import eu.hansolo.mcp.api.McpResourceHandler;
import eu.hansolo.mcp.api.McpToolHandler;
import eu.hansolo.mcp.api.PromptCall;
import eu.hansolo.mcp.api.PromptResult;
import eu.hansolo.mcp.api.ResourceCall;
import eu.hansolo.mcp.api.ResourceResult;
import eu.hansolo.mcp.api.ToolCall;
import eu.hansolo.mcp.api.ToolResult;
import eu.hansolo.mcp.auth.AuthConfig;
import eu.hansolo.mcp.auth.McpAuthenticator;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** subscriptions/listen: the filter, the acknowledgement, what is sent, and how a stream ends. */
class McpSubscriptionsTest {

    private static final String VERSION = "2026-07-28";
    private static final String BOTH    = "application/json, text/event-stream";

    private final McpConfig         config        = new McpConfig(null, 4096, 0L, "test", "1", null, 0, 20L, 2, 1);
    private final McpNotifications  notifications = new McpNotifications(config);
    private final McpRequestHandler handler       = new McpRequestHandler(McpRegistry.of(List.of(new NoopTool()), List.of(new Doc("config://a"), new Doc("config://b")), List.of(), List.of()), config, AuthConfig.disabled(), header -> Caller.ANONYMOUS, notifications);


    // ---- the request -----------------------------------------------------------------------------------------------

    @Test
    void listenNeedsEventStreamInAccept() {
        McpHttpResponse response = listen(handler, "1", filter(true, false, false), "application/json", null);
        assertEquals(406, response.status());
        assertFalse(response.isStreaming());
        assertEquals(0, notifications.activeSubscriptions());
    }

    @Test
    void aBadFilterIsAnOrdinaryJsonError() {
        for (JsonObject params : new JsonObject[] {Json.createObjectBuilder().build(), Json.createObjectBuilder().add("notifications", "yes").build(), Json.createObjectBuilder().add("notifications", Json.createObjectBuilder().add("toolsListChanged", "yes")).build(), Json.createObjectBuilder().add("notifications", Json.createObjectBuilder().add("resourceSubscriptions", "config://a")).build(), Json.createObjectBuilder().add("notifications", Json.createObjectBuilder().add("resourceSubscriptions", Json.createArrayBuilder().add(5))).build()}) {
            McpHttpResponse response = send(handler, "1", params, BOTH, null);
            assertFalse(response.isStreaming(), params.toString());
            assertEquals(-32602, json(response.body()).getJsonObject("error").getInt("code"), params.toString());
        }
        assertEquals(0, notifications.activeSubscriptions());
    }

    @Test
    void tooManyUrisAreRefused() {
        JsonArrayBuilder uris = Json.createArrayBuilder();
        for (int i = 0; i < 101; i++) {
            uris.add("config://a" + i);
        }
        McpHttpResponse response = send(handler, "1", Json.createObjectBuilder().add("notifications", Json.createObjectBuilder().add("resourceSubscriptions", uris)).build(), BOTH, null);
        assertEquals(-32602, json(response.body()).getJsonObject("error").getInt("code"));
    }

    // ---- what the server agrees to ---------------------------------------------------------------------------------

    @Test
    void theAcknowledgementListsOnlyWhatTheServerHonors() throws IOException {
        // there are no prompts, "config://zzz" does not exist
        McpHttpResponse response = listen(handler, "7", Json.createObjectBuilder().add("toolsListChanged", true).add("promptsListChanged", true).add("resourcesListChanged", true).add("resourceSubscriptions", Json.createArrayBuilder().add("config://a").add("config://zzz").add("config://a")).build(), BOTH, null);
        assertTrue(response.isStreaming());
        notifications.close();   // a stream only ends when something ends it
        List<JsonObject> events = drain(response);
        JsonObject ack = events.get(0);
        assertEquals("notifications/subscriptions/acknowledged", ack.getString("method"));
        assertEquals(7, ack.getJsonObject("params").getJsonObject("_meta").getInt("io.modelcontextprotocol/subscriptionId"));
        JsonObject honored = ack.getJsonObject("params").getJsonObject("notifications");
        assertTrue(honored.getBoolean("toolsListChanged"));
        assertTrue(honored.getBoolean("resourcesListChanged"));
        assertFalse(honored.containsKey("promptsListChanged"));
        assertEquals(List.of("config://a"), strings(honored.getJsonArray("resourceSubscriptions")));
    }

    @Test
    void whatTheClientDidNotAskForIsNeverSent() throws IOException {
        McpHttpResponse response = listen(handler, "1", filter(false, false, true), BOTH, null);
        notifications.toolsListChanged();
        notifications.resourceUpdated("config://a");
        notifications.resourcesListChanged();
        notifications.close();
        List<JsonObject> events = drain(response);
        assertEquals(List.of("notifications/subscriptions/acknowledged", "notifications/resources/list_changed"), events.subList(0, 2).stream().map(e -> e.getString("method")).toList());
        assertEquals(3, events.size(), "acknowledgement, the one requested notification, the final response");
    }

    @Test
    void resourceUpdatesReachOnlyTheSubscribersOfThatUri() throws IOException {
        McpHttpResponse response = listen(handler, "1", Json.createObjectBuilder().add("resourceSubscriptions", Json.createArrayBuilder().add("config://a")).build(), BOTH, null);
        notifications.resourceUpdated("config://b");
        notifications.resourceUpdated("config://a");
        notifications.resourceUpdated("config://a");
        notifications.close();
        List<JsonObject> events = drain(response);
        List<JsonObject> updates = events.stream().filter(e -> e.containsKey("method") && e.getString("method").equals("notifications/resources/updated")).toList();
        assertEquals(1, updates.size(), "two updates of the same uri before the stream writes are one notification");
        assertEquals("config://a", updates.get(0).getJsonObject("params").getString("uri"));
        assertEquals(1, updates.get(0).getJsonObject("params").getJsonObject("_meta").getInt("io.modelcontextprotocol/subscriptionId"));
    }

    @Test
    void repeatedListChangesBeforeTheStreamWritesAreOneNotification() throws IOException {
        McpHttpResponse response = listen(handler, "1", filter(true, false, false), BOTH, null);
        notifications.toolsListChanged();
        notifications.toolsListChanged();
        notifications.toolsListChanged();
        notifications.close();
        assertEquals(1, drain(response).stream().filter(e -> "notifications/tools/list_changed".equals(e.getString("method", ""))).count());
    }

    @Test
    void aServerSideCloseEndsWithAFinalCompleteResponse() throws IOException {
        McpHttpResponse response = listen(handler, "42", filter(true, false, false), BOTH, null);
        notifications.close();
        List<JsonObject> events = drain(response);
        JsonObject last = events.get(events.size() - 1);
        assertEquals(42, last.getInt("id"));
        assertEquals("complete", last.getJsonObject("result").getString("resultType"));
        assertEquals(42, last.getJsonObject("result").getJsonObject("_meta").getInt("io.modelcontextprotocol/subscriptionId"));
        assertEquals(0, notifications.activeSubscriptions());
    }

    // ---- a live stream ---------------------------------------------------------------------------------------------

    @Test
    void aLiveStreamGetsKeepAlivesNotificationsAndReleasesItselfWhenTheClientIsGone() throws Exception {
        McpHttpResponse response = listen(handler, "1", filter(true, false, false), BOTH, null);
        BlockingQueue<String> seen = new LinkedBlockingQueue<>();
        boolean[] gone = {false};
        Thread writer = new Thread(() -> {
            try {
                response.stream().writeTo(new McpHttpResponse.EventSink() {
                    @Override
                    public void send(String json) throws IOException {
                        if (gone[0]) {
                            throw new IOException("Broken pipe");
                        }
                        seen.add(json);
                    }

                    @Override
                    public void comment(String text) throws IOException {
                        if (gone[0]) {
                            throw new IOException("Broken pipe");
                        }
                        seen.add("comment:" + text);
                    }
                });
            } catch (IOException expected) {
                // the client went away
            }
        });
        writer.start();
        assertTrue(json(seen.poll(5, TimeUnit.SECONDS)).getString("method").contains("acknowledged"));
        assertEquals("comment:keep-alive", seen.poll(5, TimeUnit.SECONDS));
        assertEquals(1, notifications.activeSubscriptions());
        notifications.toolsListChanged();
        String next;
        do {
            next = seen.poll(5, TimeUnit.SECONDS);
            assertNotNull(next, "no tools/list_changed arrived");
        } while (next.startsWith("comment:"));
        assertEquals("notifications/tools/list_changed", json(next).getString("method"));
        gone[0] = true;
        writer.join(5_000);
        assertFalse(writer.isAlive(), "the stream must end when a write fails");
        assertEquals(0, notifications.activeSubscriptions());
    }

    // ---- limits ----------------------------------------------------------------------------------------------------

    @Test
    void streamsAreCappedPerServerAndPerCaller() throws IOException {
        McpHttpResponse first = listen(handler, "1", filter(true, false, false), BOTH, null);
        assertTrue(first.isStreaming());
        McpHttpResponse sameCaller = listen(handler, "2", filter(true, false, false), BOTH, null);
        assertEquals(503, sameCaller.status(), "per-caller limit is 1 in this test");
        notifications.close();
        drain(first);
        assertEquals(0, notifications.activeSubscriptions());
        assertEquals(503, listen(handler, "3", filter(true, false, false), BOTH, null).status(), "a stopped server accepts no new streams");
    }

    @Test
    void theServerWideLimitCountsDifferentCallers() {
        McpNotifications hub = new McpNotifications(new McpConfig(null, 4096, 0L, "t", "1", null, 0, 20L, 1, 5));
        McpAuthenticator byHeader = header -> new Caller(header == null ? "x" : header, Set.of(), Json.createObjectBuilder().build());
        McpRequestHandler h = new McpRequestHandler(McpRegistry.of(List.of(new NoopTool())), config, AuthConfig.disabled(), byHeader, hub);
        assertTrue(listen(h, "1", filter(true, false, false), BOTH, null).isStreaming());
        assertEquals(503, listen(h, "2", filter(true, false, false), BOTH, null).status());
    }

    // ---- authorization and expiry ----------------------------------------------------------------------------------

    @Test
    void resourcesTheCallerMayNotReadAreLeftOutAndAnExpiredTokenEndsTheStream() throws IOException {
        AuthConfig auth = new AuthConfig(true, "https://mcp.example.com/mcp", "https://auth.example.com", "https://auth.example.com/jwks", null, List.of(), List.of("mcp:tools", "docs"), List.of("mcp:tools"), 60, 300, 30, false);
        long exp = Instant.now().plusMillis(200).getEpochSecond();
        McpAuthenticator authenticator = header -> new Caller("alice", Set.of("mcp:tools"), Json.createObjectBuilder().add("sub", "alice").add("exp", exp).build());
        McpNotifications hub = new McpNotifications(config);
        McpRequestHandler secured = new McpRequestHandler(McpRegistry.of(List.of(new NoopTool()), List.of(new Doc("config://a"), new Doc("secret://x") {
            @Override
            public Set<String> requiredScopes() {
                return Set.of("docs");
            }
        }), List.of(), List.of()), config, auth, authenticator, hub);
        McpHttpResponse response = listen(secured, "1", Json.createObjectBuilder().add("resourceSubscriptions", Json.createArrayBuilder().add("config://a").add("secret://x")).build(), BOTH, "Bearer t");
        assertTrue(response.isStreaming());
        // nobody closes it: the token's expiry does (exp is whole seconds, so this takes at most a second or two)
        List<JsonObject> events = drain(response);
        assertEquals(List.of("config://a"), strings(events.get(0).getJsonObject("params").getJsonObject("notifications").getJsonArray("resourceSubscriptions")));
        assertEquals("complete", events.get(events.size() - 1).getJsonObject("result").getString("resultType"));
        assertEquals(0, hub.activeSubscriptions());
    }

    // ---- discovery -------------------------------------------------------------------------------------------------

    @Test
    void discoverAdvertisesListChangedAndSubscribe() {
        McpHttpResponse response = send(handler, "server/discover", "1", Json.createObjectBuilder().build(), BOTH, null);
        JsonObject capabilities = json(response.body()).getJsonObject("result").getJsonObject("capabilities");
        assertTrue(capabilities.getJsonObject("tools").getBoolean("listChanged"));
        assertTrue(capabilities.getJsonObject("resources").getBoolean("subscribe"));
        assertTrue(capabilities.getJsonObject("resources").getBoolean("listChanged"));
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private static JsonObject filter(boolean tools, boolean prompts, boolean resources) {
        return Json.createObjectBuilder().add("toolsListChanged", tools).add("promptsListChanged", prompts).add("resourcesListChanged", resources).build();
    }

    private static McpHttpResponse listen(McpRequestHandler h, String id, JsonObject notificationFilter, String accept, String authorization) {
        return send(h, id, Json.createObjectBuilder().add("notifications", notificationFilter).build(), accept, authorization);
    }

    private static McpHttpResponse send(McpRequestHandler h, String id, JsonObject params, String accept, String authorization) {
        return send(h, "subscriptions/listen", id, params, accept, authorization);
    }

    private static McpHttpResponse send(McpRequestHandler h, String method, String id, JsonObject params, String accept, String authorization) {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.put("MCP-Protocol-Version", VERSION);
        headers.put("Mcp-Method", method);
        headers.put("Accept", accept);
        if (authorization != null) {
            headers.put("Authorization", authorization);
        }
        JsonObject meta = Json.createObjectBuilder().add("io.modelcontextprotocol/protocolVersion", VERSION).add("io.modelcontextprotocol/clientCapabilities", Json.createObjectBuilder()).build();
        String body = Json.createObjectBuilder().add("jsonrpc", "2.0").add("id", Integer.parseInt(id.replaceAll("\\D", "")) ).add("method", method).add("params", Json.createObjectBuilder(params).add("_meta", meta)).build().toString();
        return h.handle(headers::get, new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static JsonObject json(String text) {
        return Json.createReader(new StringReader(text)).readObject();
    }

    /** Runs a stream to its end in this thread; only for streams that end by themselves. */
    private static List<JsonObject> drain(McpHttpResponse response) throws IOException {
        List<JsonObject> events = new ArrayList<>();
        response.stream().writeTo(text -> events.add(json(text)));
        return events;
    }

    private static List<String> strings(JsonArray array) {
        return array.stream().map(v -> ((jakarta.json.JsonString) v).getString()).toList();
    }

    private static class Doc implements McpResourceHandler {

        private final String uri;


        Doc(String uri) {
            this.uri = uri;
        }


        @Override
        public String uri() {
            return uri;
        }

        @Override
        public String name() {
            return uri.replaceAll("\\W", "-");
        }

        @Override
        public ResourceResult read(ResourceCall call) {
            return ResourceResult.text(uri, "text/plain", "x");
        }
    }

    private static final class NoopTool implements McpToolHandler {

        @Override
        public String name() {
            return "noop";
        }

        @Override
        public String description() {
            return "noop";
        }

        @Override
        public JsonObject inputSchema() {
            return Json.createObjectBuilder().add("type", "object").add("additionalProperties", false).build();
        }

        @Override
        public ToolResult call(ToolCall call) {
            return ToolResult.text("ok");
        }
    }
}
