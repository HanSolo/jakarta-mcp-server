package eu.hansolo.mcp.server;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import eu.hansolo.mcp.api.Caller;
import eu.hansolo.mcp.api.McpToolHandler;
import eu.hansolo.mcp.api.ToolCall;
import eu.hansolo.mcp.api.ToolResult;
import eu.hansolo.mcp.auth.AuthConfig;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** mcp.listen.async: the stream is marked as detachable, and many of them can wait on virtual threads at once. */
class McpAsyncListenTest {

    private static final String VERSION = "2026-07-28";
    private static final String BOTH    = "application/json, text/event-stream";


    @Test
    void streamsStayOnTheRequestThreadUnlessAsyncIsSwitchedOn() {
        McpConfig blocking = new McpConfig(null, 4096, 0L, "t", "1", null, 0, 20L, 10, 10);
        assertFalse(blocking.listenAsync());
        McpHttpResponse response = listen(handler(blocking, new McpNotifications(blocking)), "1");
        assertTrue(response.isStreaming());
        assertFalse(response.isDetached());

        McpConfig async = asyncConfig(10);
        assertTrue(async.listenAsync());
        McpHttpResponse detached = listen(handler(async, new McpNotifications(async)), "1");
        assertTrue(detached.isStreaming());
        assertTrue(detached.isDetached());
    }

    @Test
    void toolStreamsAreNeverDetachedBecauseToolsNeedTheRequestContext() {
        McpConfig async = asyncConfig(10);
        McpRequestHandler handler = handler(async, new McpNotifications(async));
        JsonObject meta = Json.createObjectBuilder().add("io.modelcontextprotocol/protocolVersion", VERSION).add("io.modelcontextprotocol/clientCapabilities", Json.createObjectBuilder()).add("progressToken", "p1").build();
        String body = Json.createObjectBuilder().add("jsonrpc", "2.0").add("id", 1).add("method", "tools/call").add("params", Json.createObjectBuilder().add("name", "noop").add("arguments", Json.createObjectBuilder()).add("_meta", meta)).build().toString();
        Map<String, String> headers = headers("tools/call");
        headers.put("Mcp-Name", "noop");
        McpHttpResponse response = handler.handle(headers::get, new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        assertTrue(response.isStreaming());
        assertFalse(response.isDetached());
    }

    @Test
    void manyStreamsWaitOnVirtualThreadsAndAllGetTheNotification() throws Exception {
        int streams = 300;
        McpConfig async = asyncConfig(streams);
        McpNotifications notifications = new McpNotifications(async);
        McpRequestHandler handler = handler(async, notifications);
        Set<Boolean> virtual = ConcurrentHashMap.newKeySet();
        AtomicInteger acknowledged = new AtomicInteger();
        AtomicInteger changed = new AtomicInteger();
        AtomicInteger finished = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(streams);
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory())) {
            for (int i = 0; i < streams; i++) {
                McpHttpResponse response = listen(handler, String.valueOf(i + 1));
                assertTrue(response.isDetached());
                executor.execute(() -> {
                    try {
                        response.stream().writeTo(text -> {
                            virtual.add(Thread.currentThread().isVirtual());
                            String method = json(text).containsKey("method") ? json(text).getString("method") : "result";
                            switch (method) {
                                case "notifications/subscriptions/acknowledged" -> acknowledged.incrementAndGet();
                                case "notifications/tools/list_changed" -> changed.incrementAndGet();
                                default -> finished.incrementAndGet();
                            }
                        });
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    } finally {
                        done.countDown();
                    }
                });
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (acknowledged.get() < streams && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(streams, acknowledged.get(), "every stream must be waiting");
            assertEquals(streams, notifications.activeSubscriptions());

            notifications.toolsListChanged();
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (changed.get() < streams && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(streams, changed.get());

            notifications.close();
            assertTrue(done.await(10, TimeUnit.SECONDS), "closing the hub must end every stream");
        }
        assertEquals(streams, finished.get(), "each stream ends with its final response");
        assertEquals(Set.of(true), virtual, "the events were written on virtual threads");
        assertEquals(0, notifications.activeSubscriptions());
    }


    private static McpConfig asyncConfig(int max) {
        return new McpConfig(null, 4096, 0L, "t", "1", null, 0, 20L, max, max, true, null);
    }

    private static McpRequestHandler handler(McpConfig config, McpNotifications notifications) {
        return new McpRequestHandler(McpRegistry.of(List.of(new NoopTool()), List.of(), List.of(), List.of()), config, AuthConfig.disabled(), header -> Caller.ANONYMOUS, notifications);
    }

    private static Map<String, String> headers(String method) {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.put("MCP-Protocol-Version", VERSION);
        headers.put("Mcp-Method", method);
        headers.put("Accept", BOTH);
        return headers;
    }

    private static McpHttpResponse listen(McpRequestHandler handler, String id) {
        JsonObject meta = Json.createObjectBuilder().add("io.modelcontextprotocol/protocolVersion", VERSION).add("io.modelcontextprotocol/clientCapabilities", Json.createObjectBuilder()).build();
        String body = Json.createObjectBuilder().add("jsonrpc", "2.0").add("id", Integer.parseInt(id)).add("method", "subscriptions/listen").add("params", Json.createObjectBuilder().add("notifications", Json.createObjectBuilder().add("toolsListChanged", true)).add("_meta", meta)).build().toString();
        return handler.handle(headers("subscriptions/listen")::get, new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static JsonObject json(String text) {
        return Json.createReader(new StringReader(text)).readObject();
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
