package eu.hansolo.mcp.server;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonValue;
import eu.hansolo.mcp.api.McpToolHandler;
import eu.hansolo.mcp.api.RequestCancelledException;
import eu.hansolo.mcp.api.ToolCall;
import eu.hansolo.mcp.api.ToolResult;
import eu.hansolo.mcp.protocol.McpProtocolException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Progress notifications and the streamed (Server-Sent Events) response of a tools/call. */
class McpStreamingTest {

    private static final String VERSION = "2026-07-28";
    private static final String BOTH    = "application/json, text/event-stream";

    private final AtomicInteger     reachedEnd = new AtomicInteger();
    private final McpRequestHandler handler    = new McpRequestHandler(McpRegistry.of(List.of(new CountTool(reachedEnd), new FailTool(), new ProtocolFailTool(), new RegressTool())), new McpConfig(null, 4096, 0L, "test", "1", null));


    @Test
    void progressNotificationsComeBeforeTheFinalResponse() throws IOException {
        McpHttpResponse response = call("count", "7", Json.createObjectBuilder().add("steps", 3).build(), "tok-1", BOTH);
        assertTrue(response.isStreaming());
        assertEquals(200, response.status());
        assertNull(response.body());
        List<JsonObject> events = events(response);
        assertEquals(4, events.size());
        for (int i = 0; i < 3; i++) {
            JsonObject notification = events.get(i);
            assertEquals("notifications/progress", notification.getString("method"));
            assertFalse(notification.containsKey("id"), "a notification has no id");
            JsonObject params = notification.getJsonObject("params");
            assertEquals("tok-1", params.getString("progressToken"));
            assertEquals(i + 1, params.getJsonNumber("progress").intValue());
            assertEquals(3, params.getJsonNumber("total").intValue());
            assertEquals("step " + (i + 1), params.getString("message"));
        }
        JsonObject last = events.get(3);
        assertEquals(7, last.getInt("id"));
        JsonObject result = last.getJsonObject("result");
        assertEquals("complete", result.getString("resultType"));
        assertEquals("counted 3", result.getJsonArray("content").getJsonObject(0).getString("text"));
        assertEquals("test", result.getJsonObject("_meta").getJsonObject("io.modelcontextprotocol/serverInfo").getString("name"));
    }

    @Test
    void anIntegerProgressTokenIsEchoedAsAnInteger() throws IOException {
        List<JsonObject> events = events(call("count", "1", Json.createObjectBuilder().add("steps", 1).build(), 42, BOTH));
        assertEquals(42, events.get(0).getJsonObject("params").getInt("progressToken"));
    }

    @Test
    void withoutAProgressTokenTheReplyIsPlainJson() {
        McpHttpResponse response = call("count", "1", Json.createObjectBuilder().add("steps", 2).build(), null, BOTH);
        assertFalse(response.isStreaming());
        assertEquals(200, response.status());
        assertEquals("counted 2", json(response).getJsonObject("result").getJsonArray("content").getJsonObject(0).getString("text"));
    }

    @Test
    void withoutEventStreamInAcceptTheReplyIsPlainJsonAndProgressIsDropped() {
        for (String accept : new String[] {null, "application/json", "*/*", "text/event-stream;q=0, application/json"}) {
            McpHttpResponse response = call("count", "1", Json.createObjectBuilder().add("steps", 2).build(), "t", accept);
            assertFalse(response.isStreaming(), String.valueOf(accept));
            assertEquals("counted 2", json(response).getJsonObject("result").getJsonArray("content").getJsonObject(0).getString("text"));
        }
    }

    @Test
    void acceptParsing() {
        assertTrue(HeaderValues.acceptsEventStream("text/event-stream"));
        assertTrue(HeaderValues.acceptsEventStream("application/json, TEXT/Event-Stream; q=0.5"));
        assertFalse(HeaderValues.acceptsEventStream("text/event-stream; q=0"));
        assertFalse(HeaderValues.acceptsEventStream("text/*"));
        assertFalse(HeaderValues.acceptsEventStream(null));
    }

    @Test
    void errorsBeforeTheToolRunsKeepTheirHttpStatus() {
        McpHttpResponse unknown = call("nope", "1", Json.createObjectBuilder().build(), "t", BOTH);
        assertFalse(unknown.isStreaming());
        assertEquals(-32602, json(unknown).getJsonObject("error").getInt("code"));

        McpHttpResponse invalid = call("count", "1", Json.createObjectBuilder().add("steps", "three").build(), "t", BOTH);
        assertFalse(invalid.isStreaming());
        assertEquals(-32602, json(invalid).getJsonObject("error").getInt("code"));

        Map<String, String> headers = headers("count", BOTH);
        headers.put("Mcp-Method", "tools/list");
        McpHttpResponse mismatch = handler.handle(headers::get, stream(body("count", "1", Json.createObjectBuilder().add("steps", 1).build(), "t")));
        assertFalse(mismatch.isStreaming());
        assertEquals(400, mismatch.status());

        McpHttpResponse badToken = call("count", "1", Json.createObjectBuilder().add("steps", 1).build(), Json.createObjectBuilder().add("x", 1).build(), BOTH);
        assertFalse(badToken.isStreaming());
        assertEquals(-32602, json(badToken).getJsonObject("error").getInt("code"));
    }

    @Test
    void aFailingToolStillEndsTheStreamWithAnErrorResult() throws IOException {
        List<JsonObject> events = events(call("fail", "5", Json.createObjectBuilder().build(), "t", BOTH));
        assertEquals(2, events.size());
        assertEquals("notifications/progress", events.get(0).getString("method"));
        JsonObject result = events.get(1).getJsonObject("result");
        assertTrue(result.getBoolean("isError"));
        assertEquals(5, events.get(1).getInt("id"));
    }

    @Test
    void aProtocolErrorInTheToolBecomesAJsonRpcErrorEvent() throws IOException {
        List<JsonObject> events = events(call("protocol_fail", "6", Json.createObjectBuilder().build(), "t", BOTH));
        assertEquals(1, events.size());
        assertEquals(6, events.get(0).getInt("id"));
        assertEquals(-32602, events.get(0).getJsonObject("error").getInt("code"));
    }

    @Test
    void progressThatDoesNotIncreaseIsAToolFailure() throws IOException {
        List<JsonObject> events = events(call("regress", "8", Json.createObjectBuilder().build(), "t", BOTH));
        assertEquals(2, events.size(), "the first report went out, the second was rejected");
        assertTrue(events.get(1).getJsonObject("result").getBoolean("isError"));
    }

    @Test
    void closingTheStreamCancelsTheRequestAndSendsNothingMore() throws IOException {
        McpHttpResponse response = call("count", "9", Json.createObjectBuilder().add("steps", 5).build(), "t", BOTH);
        List<String> sent = new ArrayList<>();
        response.stream().writeTo(json -> {
            if (sent.size() == 2) {
                throw new IOException("Broken pipe");
            }
            sent.add(json);
        });
        assertEquals(2, sent.size());
        assertEquals(0, reachedEnd.get(), "the tool must have stopped at the third report");
    }

    @Test
    void anOrdinaryCallToAToolThatReportsProgressStillWorks() {
        // plain JSON path: the tool reports into a no-op reporter
        McpHttpResponse response = call("count", "1", Json.createObjectBuilder().add("steps", 4).build(), null, null);
        assertEquals("counted 4", json(response).getJsonObject("result").getJsonArray("content").getJsonObject(0).getString("text"));
        assertEquals(1, reachedEnd.get());
    }

    @Test
    void aQuietCallGetsKeepAliveCommentsAndNoneAfterTheResult() throws Exception {
        McpRequestHandler quick = withKeepAlive(new SlowTool());
        List<String> lines = new ArrayList<>();
        McpHttpResponse response = quick.handle(headers("slow", BOTH)::get, stream(body("slow", "1", Json.createObjectBuilder().add("steps", 300).build(), "t")));
        response.stream().writeTo(recorder(lines, false));
        assertTrue(lines.stream().filter(l -> l.equals(":keep-alive")).count() >= 3, lines.toString());
        assertTrue(lines.get(lines.size() - 1).startsWith("{"), "the result is the last thing written: " + lines);
        assertEquals(1, lines.stream().filter(l -> l.startsWith("{")).count());
    }

    @Test
    void aVanishedClientCancelsTheCallThroughTheKeepAlive() throws Exception {
        SlowTool tool = new SlowTool();
        McpRequestHandler quick = withKeepAlive(tool);
        List<String> lines = new ArrayList<>();
        McpHttpResponse response = quick.handle(headers("slow", BOTH)::get, stream(body("slow", "1", Json.createObjectBuilder().add("steps", 5000).build(), "t")));
        response.stream().writeTo(recorder(lines, true));
        assertTrue(tool.sawCancel.get(), "the tool did not see the cancellation");
        assertTrue(lines.stream().noneMatch(l -> l.startsWith("{")), "nothing is sent for a cancelled call: " + lines);
    }

    @Test
    void aFastCallWritesNoComment() throws Exception {
        McpRequestHandler quick = withKeepAlive(new SlowTool());
        List<String> lines = new ArrayList<>();
        McpHttpResponse response = quick.handle(headers("slow", BOTH)::get, stream(body("slow", "1", Json.createObjectBuilder().add("steps", 0).build(), "t")));
        response.stream().writeTo(recorder(lines, false));
        assertEquals(1, lines.size(), lines.toString());
    }

    private static McpRequestHandler withKeepAlive(McpToolHandler tool) {
        return new McpRequestHandler(McpRegistry.of(List.of(tool)), new McpConfig(null, 4096, 0L, "test", "1", null, 0, 20L, 100, 10));
    }

    /** Records events as their JSON text and comments as {@code :text}; with {@code failOnComment} a comment fails like a closed connection. */
    private static McpHttpResponse.EventSink recorder(List<String> lines, boolean failOnComment) {
        return new McpHttpResponse.EventSink() {
            @Override
            public void send(String json) {
                synchronized (lines) {
                    lines.add(json);
                }
            }

            @Override
            public void comment(String text) throws IOException {
                if (failOnComment) {
                    throw new IOException("connection reset");
                }
                synchronized (lines) {
                    lines.add(":" + text);
                }
            }
        };
    }

    /**
     * Logging is deprecated in 2026-07-28 and not implemented. The specification forbids {@code notifications/message} for a request without
     * {@code io.modelcontextprotocol/logLevel}; a request that does ask for a level simply gets none, because the server has no logging to offer.
     */
    @Test
    void noLogMessagesAreSentWhetherOrNotALogLevelIsRequested() throws IOException {
        for (String level : new String[] {null, "debug", "error"}) {
            JsonObject original = Json.createReader(new StringReader(body("count", "9", Json.createObjectBuilder().add("steps", 2).build(), "t"))).readObject();
            JsonObject params   = original.getJsonObject("params");
            JsonObjectBuilder meta = Json.createObjectBuilder(params.getJsonObject("_meta"));
            if (level != null) {
                meta.add("io.modelcontextprotocol/logLevel", level);
            }
            JsonObject changed = Json.createObjectBuilder(original).add("params", Json.createObjectBuilder(params).add("_meta", meta)).build();
            Map<String, String> headers = headers("count", BOTH);
            McpHttpResponse response = handler.handle(headers::get, stream(changed.toString()));
            assertTrue(response.isStreaming(), String.valueOf(level));
            List<JsonObject> events = events(response);
            assertEquals(3, events.size(), "two progress notifications and the result, for log level " + level);
            for (JsonObject event : events) {
                assertFalse(event.containsKey("method") && "notifications/message".equals(event.getString("method")), "log level " + level);
            }
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private McpHttpResponse call(String tool, String id, JsonObject arguments, Object progressToken, String accept) {
        Map<String, String> headers = headers(tool, accept);
        return handler.handle(headers::get, stream(body(tool, id, arguments, progressToken)));
    }

    private static Map<String, String> headers(String tool, String accept) {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.put("MCP-Protocol-Version", VERSION);
        headers.put("Mcp-Method", "tools/call");
        headers.put("Mcp-Name", tool);
        if (accept != null) {
            headers.put("Accept", accept);
        }
        return headers;
    }

    private static ByteArrayInputStream stream(String raw) {
        return new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String body(String tool, String id, JsonObject arguments, Object progressToken) {
        JsonObjectBuilder meta = Json.createObjectBuilder().add("io.modelcontextprotocol/protocolVersion", VERSION).add("io.modelcontextprotocol/clientCapabilities", Json.createObjectBuilder());
        switch (progressToken) {
            case null -> { }
            case String s -> meta.add("progressToken", s);
            case Integer i -> meta.add("progressToken", i);
            case JsonValue v -> meta.add("progressToken", v);
            default -> throw new IllegalArgumentException();
        }
        JsonObject params = Json.createObjectBuilder().add("name", tool).add("arguments", arguments).add("_meta", meta).build();
        return Json.createObjectBuilder().add("jsonrpc", "2.0").add("id", Integer.parseInt(id)).add("method", "tools/call").add("params", params).build().toString();
    }

    private static JsonObject json(McpHttpResponse response) {
        return Json.createReader(new StringReader(response.body())).readObject();
    }

    private static List<JsonObject> events(McpHttpResponse response) throws IOException {
        List<JsonObject> events = new ArrayList<>();
        response.stream().writeTo(json -> events.add(Json.createReader(new StringReader(json)).readObject()));
        return events;
    }

    // ---- tools -----------------------------------------------------------------------------------------------------

    private abstract static class Base implements McpToolHandler {

        @Override
        public String description() {
            return name();
        }

        @Override
        public JsonObject inputSchema() {
            return Json.createObjectBuilder().add("type", "object").add("properties", Json.createObjectBuilder().add("steps", Json.createObjectBuilder().add("type", "integer"))).add("additionalProperties", false).build();
        }
    }

    private static final class CountTool extends Base {

        private final AtomicInteger reachedEnd;


        CountTool(AtomicInteger reachedEnd) {
            this.reachedEnd = reachedEnd;
        }


        @Override
        public String name() {
            return "count";
        }

        @Override
        public ToolResult call(ToolCall call) {
            int steps = call.arguments().getInt("steps", 3);
            for (int i = 1; i <= steps; i++) {
                call.progress(i, (double) steps, "step " + i);
            }
            reachedEnd.incrementAndGet();
            return ToolResult.text("counted " + steps);
        }
    }

    private static final class FailTool extends Base {

        @Override
        public String name() {
            return "fail";
        }

        @Override
        public ToolResult call(ToolCall call) {
            call.progress(1, null, null);
            throw new IllegalStateException("secret detail");
        }
    }

    private static final class ProtocolFailTool extends Base {

        @Override
        public String name() {
            return "protocol_fail";
        }

        @Override
        public ToolResult call(ToolCall call) {
            throw McpProtocolException.invalidArguments("no good");
        }
    }

    private static final class RegressTool extends Base {

        @Override
        public String name() {
            return "regress";
        }

        @Override
        public ToolResult call(ToolCall call) {
            call.progress(2, null, null);
            call.progress(2, null, null);
            return ToolResult.text("unreachable");
        }
    }

    /** Works for {@code steps} milliseconds without reporting progress; stops early when the call is cancelled. */
    private static final class SlowTool extends Base {

        final java.util.concurrent.atomic.AtomicBoolean sawCancel = new java.util.concurrent.atomic.AtomicBoolean();

        @Override
        public String name() {
            return "slow";
        }

        @Override
        public ToolResult call(ToolCall call) {
            long end = System.currentTimeMillis() + call.arguments().getInt("steps");
            while (System.currentTimeMillis() < end) {
                if (call.isCancelled()) {
                    sawCancel.set(true);
                    return ToolResult.text("stopped");
                }
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return ToolResult.text("done");
        }
    }
}
