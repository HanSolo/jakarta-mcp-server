package eu.hansolo.mcp.server;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import eu.hansolo.mcp.api.Caller;
import eu.hansolo.mcp.api.McpResourceHandler;
import eu.hansolo.mcp.api.McpToolHandler;
import eu.hansolo.mcp.api.ResourceCall;
import eu.hansolo.mcp.api.ResourceResult;
import eu.hansolo.mcp.api.ToolCall;
import eu.hansolo.mcp.api.ToolResult;
import eu.hansolo.mcp.auth.AuthConfig;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Passing changes to other instances: the message, the hand-over, and the ways it can go wrong. */
class McpNotificationBridgeTest {

    private static final String VERSION = "2026-07-28";
    private static final String BOTH    = "application/json, text/event-stream";

    private final McpConfig config = new McpConfig(null, 4096, 0L, "test", "1", null, 0, 20L, 5, 5);


    // ---- the message -----------------------------------------------------------------------------------------------

    @Test
    void aChangeSurvivesEncodingAndDecoding() {
        for (McpChange change : List.of(new McpChange("a1", McpChange.Kind.TOOLS_LIST_CHANGED, null), new McpChange("a1", McpChange.Kind.PROMPTS_LIST_CHANGED, null), new McpChange("a1", McpChange.Kind.RESOURCES_LIST_CHANGED, null), new McpChange("a1", McpChange.Kind.RESOURCE_UPDATED, "ticket://42?x=\"y\"&z=ä"))) {
            assertEquals(change, McpChange.decode(change.encode()), change.toString());
        }
    }

    @Test
    void aChangeNeedsAnOriginAndMatchingUri() {
        assertThrows(IllegalArgumentException.class, () -> new McpChange(" ", McpChange.Kind.TOOLS_LIST_CHANGED, null));
        assertThrows(IllegalArgumentException.class, () -> new McpChange(null, McpChange.Kind.TOOLS_LIST_CHANGED, null));
        assertThrows(IllegalArgumentException.class, () -> new McpChange("a", McpChange.Kind.RESOURCE_UPDATED, null));
        assertThrows(IllegalArgumentException.class, () -> new McpChange("a", McpChange.Kind.RESOURCE_UPDATED, " "));
        assertThrows(IllegalArgumentException.class, () -> new McpChange("a", McpChange.Kind.TOOLS_LIST_CHANGED, "ticket://1"));
    }

    @Test
    void decodingRefusesWhatIsNotAChange() {
        String tooLong = "{\"v\":1,\"o\":\"a\",\"k\":\"resource\",\"u\":\"" + "x".repeat(McpChange.MAX_ENCODED_LENGTH) + "\"}";
        for (String text : new String[] {null, "", "x", "[]", "{}", "{\"v\":2,\"o\":\"a\",\"k\":\"tools\"}", "{\"v\":1,\"k\":\"tools\"}", "{\"v\":1,\"o\":\"a\"}", "{\"v\":1,\"o\":\"a\",\"k\":\"nope\"}", "{\"v\":1,\"o\":\"a\",\"k\":\"resource\"}", "{\"v\":1,\"o\":\"a\",\"k\":\"tools\",\"u\":\"x://y\"}", "{\"v\":1,\"o\":\"a\",\"k\":\"resource\",\"u\":5}", "{\"v\":1,\"o\":5,\"k\":\"tools\"}", tooLong}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> McpChange.decode(text));
            assertFalse(e.getMessage().contains("x://y"), "the message must not repeat what was sent");
        }
    }

    // ---- what is passed on -----------------------------------------------------------------------------------------

    @Test
    void everyAnnouncementIsPassedToTheBridgeInOrderWithTheOriginOfThisInstance() throws Exception {
        Recorder bridge = new Recorder();
        McpNotifications notifications = new McpNotifications(config, () -> List.of(bridge));
        notifications.toolsListChanged();
        notifications.promptsListChanged();
        notifications.resourcesListChanged();
        notifications.resourceUpdated("config://a");
        notifications.resourceUpdated(" ");   // not an announcement
        notifications.resourceUpdated(null);
        List<McpChange> seen = bridge.take(4);
        notifications.close();
        assertEquals(List.of(McpChange.Kind.TOOLS_LIST_CHANGED, McpChange.Kind.PROMPTS_LIST_CHANGED, McpChange.Kind.RESOURCES_LIST_CHANGED, McpChange.Kind.RESOURCE_UPDATED), seen.stream().map(McpChange::kind).toList());
        assertEquals("config://a", seen.get(3).uri());
        for (McpChange change : seen) {
            assertEquals(notifications.instanceId(), change.origin());
        }
        assertTrue(bridge.seen.isEmpty(), "nothing else was announced");
    }

    @Test
    void theBridgesAreLookedUpOnFirstUseAndOnlyOnce() throws Exception {
        AtomicInteger lookups = new AtomicInteger();
        Recorder bridge = new Recorder();
        McpNotifications notifications = new McpNotifications(config, () -> {
            lookups.incrementAndGet();
            return List.of(bridge);
        });
        assertEquals(0, lookups.get(), "a bridge usually injects this bean, so the lookup cannot happen while it is built");
        notifications.toolsListChanged();
        notifications.promptsListChanged();
        bridge.take(2);
        notifications.close();
        assertEquals(1, lookups.get());
    }

    @Test
    void severalBridgesAreAllUsed() throws Exception {
        Recorder first  = new Recorder();
        Recorder second = new Recorder();
        McpNotifications notifications = new McpNotifications(config, () -> List.of(first, second));
        notifications.toolsListChanged();
        assertEquals(1, first.take(1).size());
        assertEquals(1, second.take(1).size());
        notifications.close();
    }

    @Test
    void aFailedLookupNeitherBreaksTheCallerNorTheClientsOfThisInstance() throws Exception {
        McpNotifications notifications = new McpNotifications(config, () -> {
            throw new IllegalStateException("container not ready");
        });
        McpHttpResponse stream = listen(notifications, Json.createObjectBuilder().add("toolsListChanged", true).build());
        notifications.toolsListChanged();
        notifications.close();
        assertTrue(methods(stream).contains("notifications/tools/list_changed"));
    }

    @Test
    void registrationsAreNotPassedOn() throws Exception {
        Recorder bridge = new Recorder();
        McpNotifications notifications = new McpNotifications(config, () -> List.of(bridge));
        McpRegistry registry = McpRegistry.of(List.of(new NoopTool()));
        McpRegistrations registrations = new McpRegistrations(registry, notifications, AuthConfig.disabled());
        McpHttpResponse stream = listen(notifications, Json.createObjectBuilder().add("toolsListChanged", true).build());
        registrations.replaceTool(new NoopTool());
        notifications.resourcesListChanged();   // a later announcement proves the earlier call had its chance
        List<McpChange> seen = bridge.take(1);
        notifications.close();
        assertEquals(McpChange.Kind.RESOURCES_LIST_CHANGED, seen.get(0).kind());
        assertTrue(bridge.seen.isEmpty());
        assertTrue(methods(stream).contains("notifications/tools/list_changed"), "the clients of this instance are told about a registration");
    }

    // ---- what is received ------------------------------------------------------------------------------------------

    @Test
    void receivingTellsTheLocalClientsAndNeverPassesTheChangeOnAgain() throws Exception {
        Recorder bridge = new Recorder();
        McpNotifications notifications = new McpNotifications(config, () -> List.of(bridge));
        McpHttpResponse stream = listen(notifications, Json.createObjectBuilder().add("toolsListChanged", true).add("resourceSubscriptions", Json.createArrayBuilder().add("config://a")).build());
        notifications.receive(new McpChange("another-instance", McpChange.Kind.TOOLS_LIST_CHANGED, null));
        notifications.receive(new McpChange("another-instance", McpChange.Kind.RESOURCE_UPDATED, "config://a"));
        notifications.resourcesListChanged();   // the only thing this instance announces itself
        bridge.take(1);
        notifications.close();
        List<String> methods = methods(stream);
        assertTrue(methods.contains("notifications/tools/list_changed"), methods.toString());
        assertTrue(methods.contains("notifications/resources/updated"), methods.toString());
        assertTrue(bridge.seen.isEmpty(), "received changes are not published");
    }

    @Test
    void aChangeWithTheOriginOfThisInstanceIsIgnored() throws Exception {
        McpNotifications notifications = new McpNotifications(config);
        McpHttpResponse stream = listen(notifications, Json.createObjectBuilder().add("toolsListChanged", true).build());
        notifications.receive(new McpChange(notifications.instanceId(), McpChange.Kind.TOOLS_LIST_CHANGED, null));
        notifications.close();
        assertEquals(List.of("notifications/subscriptions/acknowledged", "response"), methods(stream));
        assertThrows(NullPointerException.class, () -> notifications.receive(null));
    }

    @Test
    void everyInstanceHasItsOwnId() {
        assertFalse(new McpNotifications(config).instanceId().equals(new McpNotifications(config).instanceId()));
    }

    @Test
    void aChangeReachesTheClientsOfAnotherInstanceEvenWhenTheTopicEchoesToTheSender() throws Exception {
        McpNotifications[] both      = new McpNotifications[2];
        CountDownLatch     delivered = new CountDownLatch(2);
        McpNotificationBridge topic = change -> {
            String wire = change.encode();
            for (McpNotifications instance : both) {   // like a topic: everybody gets it, the sender too
                instance.receive(McpChange.decode(wire));
            }
            delivered.countDown();
        };
        McpNotifications a = new McpNotifications(config, () -> List.of(topic));
        McpNotifications b = new McpNotifications(config);
        both[0] = a;
        both[1] = b;
        JsonObject filter = Json.createObjectBuilder().add("toolsListChanged", true).add("resourceSubscriptions", Json.createArrayBuilder().add("config://a")).build();
        McpHttpResponse onA = listen(a, filter);
        McpHttpResponse onB = listen(b, filter);
        a.toolsListChanged();
        a.resourceUpdated("config://a");
        assertTrue(delivered.await(5, TimeUnit.SECONDS));
        a.close();
        b.close();
        for (List<String> methods : List.of(methods(onA), methods(onB))) {
            assertEquals("notifications/subscriptions/acknowledged", methods.get(0));
            assertTrue(methods.contains("notifications/tools/list_changed"), methods.toString());
            assertTrue(methods.contains("notifications/resources/updated"), methods.toString());
        }
    }

    // ---- when the bridge misbehaves --------------------------------------------------------------------------------

    @Test
    void aFailingBridgeLosesOnlyThatChange() throws Exception {
        Recorder bridge = new Recorder();
        bridge.failures.add("boom");
        McpNotifications notifications = new McpNotifications(config, () -> List.of(bridge));
        McpHttpResponse stream = listen(notifications, Json.createObjectBuilder().add("toolsListChanged", true).build());
        notifications.toolsListChanged();   // the bridge throws on this one
        assertTrue(bridge.failed.await(5, TimeUnit.SECONDS));
        notifications.promptsListChanged();
        List<McpChange> seen = bridge.take(1);
        notifications.close();
        assertEquals(McpChange.Kind.PROMPTS_LIST_CHANGED, seen.get(0).kind());
        assertTrue(methods(stream).contains("notifications/tools/list_changed"), "the clients of this instance were told");
    }

    @Test
    void aSlowBridgeNeverBlocksTheCaller() throws Exception {
        Recorder bridge = new Recorder();
        bridge.gate = new CountDownLatch(1);
        McpNotifications notifications = new McpNotifications(config, () -> List.of(bridge));
        CountDownLatch done = new CountDownLatch(1);
        Thread caller = new Thread(() -> {
            for (int i = 0; i < 20; i++) {
                notifications.toolsListChanged();
                notifications.resourceUpdated("config://" + i);
            }
            done.countDown();
        });
        caller.setDaemon(true);
        caller.start();
        assertTrue(done.await(5, TimeUnit.SECONDS), "the caller waited for the bridge");
        bridge.gate.countDown();
        assertEquals(McpChange.Kind.TOOLS_LIST_CHANGED, bridge.take(1).get(0).kind());
        notifications.close();
    }

    @Test
    void aChangeThatIsStillWaitingIsNotQueuedAgain() throws Exception {
        Recorder bridge = new Recorder();
        bridge.gate = new CountDownLatch(1);
        McpNotifications notifications = new McpNotifications(config, () -> List.of(bridge));
        notifications.toolsListChanged();
        assertTrue(bridge.entered.await(5, TimeUnit.SECONDS), "the bridge holds the first change");
        notifications.resourceUpdated("config://a");
        notifications.resourceUpdated("config://a");
        notifications.resourceUpdated("config://b");
        notifications.resourceUpdated("config://a");
        bridge.gate.countDown();
        List<McpChange> seen = bridge.take(3);
        notifications.close();
        assertEquals(List.of("tools", "config://a", "config://b"), seen.stream().map(c -> c.uri() == null ? "tools" : c.uri()).toList());
        assertTrue(bridge.seen.isEmpty());
    }

    @Test
    void aFullQueueDropsChangesInsteadOfBlocking() throws Exception {
        Recorder bridge = new Recorder();
        bridge.gate = new CountDownLatch(1);
        McpNotifications notifications = new McpNotifications(config, () -> List.of(bridge));
        notifications.toolsListChanged();
        assertTrue(bridge.entered.await(5, TimeUnit.SECONDS));
        for (int i = 0; i < 1100; i++) {
            notifications.resourceUpdated("config://r" + i);   // 1000 fit, the rest are dropped
        }
        bridge.gate.countDown();
        List<McpChange> seen = bridge.take(1001);
        notifications.close();
        assertEquals("config://r0", seen.get(1).uri());
        assertEquals("config://r999", seen.get(1000).uri());
    }

    @Test
    void closingStopsThePublisher() throws Exception {
        Recorder bridge = new Recorder();
        McpNotifications notifications = new McpNotifications(config, () -> List.of(bridge));
        notifications.toolsListChanged();
        bridge.take(1);
        assertTrue(publisherAlive());
        notifications.close();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (publisherAlive() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertFalse(publisherAlive());
        notifications.toolsListChanged();
        Thread.sleep(50);
        assertTrue(bridge.seen.isEmpty(), "nothing is published after close");
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private static boolean publisherAlive() {
        return Thread.getAllStackTraces().keySet().stream().anyMatch(t -> t.getName().equals("mcp-notification-bridge") && t.isAlive());
    }

    private McpHttpResponse listen(McpNotifications notifications, JsonObject filter) {
        McpRequestHandler handler = new McpRequestHandler(McpRegistry.of(List.of(new NoopTool()), List.of(new Doc("config://a")), List.of(), List.of()), config, AuthConfig.disabled(), header -> Caller.ANONYMOUS, notifications);
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.put("MCP-Protocol-Version", VERSION);
        headers.put("Mcp-Method", "subscriptions/listen");
        headers.put("Accept", BOTH);
        JsonObject meta = Json.createObjectBuilder().add("io.modelcontextprotocol/protocolVersion", VERSION).add("io.modelcontextprotocol/clientCapabilities", Json.createObjectBuilder()).build();
        String body = Json.createObjectBuilder().add("jsonrpc", "2.0").add("id", 1).add("method", "subscriptions/listen").add("params", Json.createObjectBuilder().add("notifications", filter).add("_meta", meta)).build().toString();
        McpHttpResponse response = handler.handle(headers::get, new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        assertTrue(response.isStreaming(), response.body());
        return response;
    }

    /** Runs a stream to its end in this thread, so close the hub first. */
    private static List<String> methods(McpHttpResponse response) throws IOException {
        List<String> methods = new ArrayList<>();
        response.stream().writeTo(text -> methods.add(Json.createReader(new StringReader(text)).readObject().getString("method", "response")));
        return methods;
    }

    private static final class Recorder implements McpNotificationBridge {

        final BlockingQueue<McpChange> seen     = new LinkedBlockingQueue<>();
        final BlockingQueue<String>    failures = new LinkedBlockingQueue<>();
        final CountDownLatch           entered  = new CountDownLatch(1);
        final CountDownLatch           failed   = new CountDownLatch(1);
        volatile CountDownLatch        gate;


        @Override
        public void publish(McpChange change) throws Exception {
            entered.countDown();
            CountDownLatch waiting = gate;
            if (waiting != null) {
                waiting.await();
            }
            if (failures.poll() != null) {
                failed.countDown();
                throw new IllegalStateException("the broker is down");
            }
            seen.add(change);
        }

        /** Waits for this many changes and returns them. */
        List<McpChange> take(int count) throws InterruptedException {
            List<McpChange> taken = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                McpChange change = seen.poll(5, TimeUnit.SECONDS);
                assertNotNull(change, "change " + (i + 1) + " of " + count + " never arrived");
                taken.add(change);
            }
            return taken;
        }
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
