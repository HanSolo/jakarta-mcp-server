package eu.hansolo.mcp.server;

import eu.hansolo.mcp.api.Caller;
import eu.hansolo.mcp.api.McpToolHandler;
import eu.hansolo.mcp.api.ToolCall;
import eu.hansolo.mcp.api.ToolResult;
import eu.hansolo.mcp.auth.AuthConfig;
import eu.hansolo.mcp.protocol.ErrorCodes;
import eu.hansolo.mcp.tasks.InMemoryTaskStore;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** notifications/tasks over subscriptions/listen: what is acknowledged, what is pushed, and when. */
class McpTaskNotificationsTest {

    private static final String VERSION = "2026-07-28";
    private static final String TASKS   = "io.modelcontextprotocol/tasks";
    private static final String BOTH    = "application/json, text/event-stream";

    private final McpConfig         config        = new McpConfig(null, 4096, 0L, "test", "1", null, 0, 20L, 2, 1);
    private final McpRegistry       registry      = McpRegistry.of(List.of(new Slow()));
    private final McpNotifications  notifications = new McpNotifications(config);
    private final TaskService       service       = new TaskService(new InMemoryTaskStore(), new TaskConfig(true, 3_600_000L, 2_000L, 1024 * 1024, 100, null, 10_000L, 30_000L, 30_000L, 20L, 60_000L, 20L), registry, Clock.systemUTC(), Executors.newVirtualThreadPerTaskExecutor());
    private final McpRequestHandler handler       = new McpRequestHandler(registry, config, AuthConfig.disabled(), header -> Caller.ANONYMOUS, notifications, McpExtensions.none(), service);

    {
        service.onChange(notifications::taskChanged);
    }


    @Test
    void aWatchedTaskIsReportedFromItsCurrentStateToItsResult() throws Exception {
        String id = createTask();
        BlockingQueue<JsonObject> events = open(declared(), Json.createArrayBuilder().add(id).add("nope"));
        JsonObject ack = events.poll(5, TimeUnit.SECONDS);
        assertEquals("notifications/subscriptions/acknowledged", ack.getString("method"));
        assertEquals(List.of(id), strings(ack.getJsonObject("params").getJsonObject("notifications").getJsonArray("taskIds")));
        JsonObject last = null;
        for (int i = 0; i < 50; i++) {
            JsonObject event = events.poll(5, TimeUnit.SECONDS);
            assertEquals("notifications/tasks", event.getString("method"));
            assertEquals(id, event.getJsonObject("params").getString("taskId"));
            assertFalse(event.getJsonObject("params").containsKey("resultType"));
            last = event;
            if (event.getJsonObject("params").getString("status").equals("completed")) {
                break;
            }
        }
        JsonObject params = last.getJsonObject("params");
        assertEquals("completed", params.getString("status"));
        assertEquals("finished", params.getJsonObject("result").getJsonArray("content").getJsonObject(0).getString("text"));
        assertTrue(params.getJsonObject("_meta").containsKey("io.modelcontextprotocol/subscriptionId"));
        notifications.close();
    }

    @Test
    void aFinishedTaskIsReportedOnceAndNotWatchedAnyMore() throws Exception {
        String id = createTask();
        for (int i = 0; i < 500 && !service.get(id, Caller.ANONYMOUS).getString("status").equals("completed"); i++) {
            Thread.sleep(10);
        }
        BlockingQueue<JsonObject> events = open(declared(), Json.createArrayBuilder().add(id));
        events.poll(5, TimeUnit.SECONDS);   // acknowledgement
        JsonObject only = events.poll(5, TimeUnit.SECONDS);
        assertEquals("completed", only.getJsonObject("params").getString("status"));
        assertEquals(null, events.poll(300, TimeUnit.MILLISECONDS), "nothing more for a finished task");
        notifications.close();
    }

    @Test
    void askingForTaskNotificationsNeedsTheExtension() {
        McpHttpResponse response = listen(null, Json.createArrayBuilder().add("abc"));
        assertFalse(response.isStreaming());
        assertEquals(ErrorCodes.MISSING_REQUIRED_CLIENT_CAPABILITY, json(response.body()).getJsonObject("error").getInt("code"));
        assertEquals(0, notifications.activeSubscriptions());
    }

    @Test
    void taskIdsMustBeAShortListOfStrings() {
        for (JsonObjectBuilder filter : List.of(Json.createObjectBuilder().add("taskIds", "abc"), Json.createObjectBuilder().add("taskIds", Json.createArrayBuilder().add(5)), Json.createObjectBuilder().add("taskIds", Json.createArrayBuilder().add("x".repeat(129))))) {
            McpHttpResponse response = listenWith(declared(), filter.build());
            assertFalse(response.isStreaming());
            assertEquals(ErrorCodes.INVALID_PARAMS, json(response.body()).getJsonObject("error").getInt("code"));
        }
        JsonArrayBuilder many = Json.createArrayBuilder();
        for (int i = 0; i < 51; i++) {
            many.add("t" + i);
        }
        assertEquals(ErrorCodes.INVALID_PARAMS, json(listen(declared(), many).body()).getJsonObject("error").getInt("code"));
        assertEquals(0, notifications.activeSubscriptions());
    }


    private String createTask() {
        JsonObject created = json(send("tools/call", "slow", Json.createObjectBuilder().add("name", "slow").build(), declared(), "application/json").body()).getJsonObject("result");
        assertEquals("task", created.getString("resultType"));
        return created.getString("taskId");
    }

    private BlockingQueue<JsonObject> open(JsonObject capabilities, JsonArrayBuilder taskIds) {
        McpHttpResponse response = listen(capabilities, taskIds);
        assertTrue(response.isStreaming());
        BlockingQueue<JsonObject> events = new LinkedBlockingQueue<>();
        Thread.ofVirtual().start(() -> {
            try {
                response.stream().writeTo(text -> events.add(json(text)));
            } catch (Exception e) {
                // the stream ends when the test closes the notifications
            }
        });
        return events;
    }

    private McpHttpResponse listen(JsonObject capabilities, JsonArrayBuilder taskIds) {
        return listenWith(capabilities, Json.createObjectBuilder().add("taskIds", taskIds).build());
    }

    private McpHttpResponse listenWith(JsonObject capabilities, JsonObject filter) {
        return send("subscriptions/listen", null, Json.createObjectBuilder().add("notifications", filter).build(), capabilities, BOTH);
    }

    private McpHttpResponse send(String method, String name, JsonObject params, JsonObject capabilities, String accept) {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.put("MCP-Protocol-Version", VERSION);
        headers.put("Mcp-Method", method);
        headers.put("Accept", accept);
        if (name != null) {
            headers.put("Mcp-Name", name);
        }
        JsonObject meta = Json.createObjectBuilder().add("io.modelcontextprotocol/protocolVersion", VERSION).add("io.modelcontextprotocol/clientCapabilities", capabilities == null ? Json.createObjectBuilder().build() : capabilities).build();
        String body = Json.createObjectBuilder().add("jsonrpc", "2.0").add("id", 1).add("method", method).add("params", Json.createObjectBuilder(params).add("_meta", meta)).build().toString();
        return handler.handle(headers::get, new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static JsonObject declared() {
        return Json.createObjectBuilder().add("extensions", Json.createObjectBuilder().add(TASKS, Json.createObjectBuilder())).build();
    }

    private static JsonObject json(String text) {
        return Json.createReader(new StringReader(text)).readObject();
    }

    private static List<String> strings(JsonArray array) {
        List<String> out = new ArrayList<>();
        array.forEach(v -> out.add(((jakarta.json.JsonString) v).getString()));
        return out;
    }


    private static class Slow implements McpToolHandler {

        @Override
        public String name() {
            return "slow";
        }

        @Override
        public String description() {
            return "finishes after a moment";
        }

        @Override
        public JsonObject inputSchema() {
            return Json.createObjectBuilder().add("type", "object").build();
        }

        @Override
        public ToolResult call(ToolCall call) {
            return ToolResult.task(ctx -> {
                TimeUnit.MILLISECONDS.sleep(150);
                return ToolResult.text("finished");
            });
        }
    }
}
