package eu.hansolo.mcp.api;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.Test;

import eu.hansolo.mcp.server.McpHttpResponse;
import eu.hansolo.mcp.server.McpConfig;
import eu.hansolo.mcp.server.McpRegistry;
import eu.hansolo.mcp.server.McpRequestHandler;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reading traceparent, tracestate and baggage from the request's _meta. */
class TraceContextTest {

    private static final String TP = "00-0af7651916cd43dd8448eb211c80319c-00f067aa0ba902b7-01";


    @Test
    void validAndInvalidTraceparents() {
        assertTrue(TraceContext.isValidTraceparent(TP));
        assertTrue(TraceContext.isValidTraceparent("01-0af7651916cd43dd8448eb211c80319c-00f067aa0ba902b7-00-extra"));
        assertFalse(TraceContext.isValidTraceparent("00-0af7651916cd43dd8448eb211c80319c-00f067aa0ba902b7-01-extra"));
        assertFalse(TraceContext.isValidTraceparent("ff-0af7651916cd43dd8448eb211c80319c-00f067aa0ba902b7-01"));
        assertFalse(TraceContext.isValidTraceparent("00-00000000000000000000000000000000-00f067aa0ba902b7-01"));
        assertFalse(TraceContext.isValidTraceparent("00-0af7651916cd43dd8448eb211c80319c-0000000000000000-01"));
        assertFalse(TraceContext.isValidTraceparent("00-0AF7651916CD43DD8448EB211C80319C-00f067aa0ba902b7-01"));
        assertFalse(TraceContext.isValidTraceparent("00-0af7651916cd43dd8448eb211c80319c-00f067aa0ba902b7"));
        assertFalse(TraceContext.isValidTraceparent(""));
        assertFalse(TraceContext.isValidTraceparent(null));
    }

    @Test
    void readsThePartsOfAValidContext() {
        TraceContext trace = TraceContext.from(Json.createObjectBuilder().add("traceparent", TP).add("tracestate", "vendor=a,other=b").add("baggage", "user=alice").add("progressToken", "x").build());
        assertEquals(TP, trace.traceparent());
        assertEquals("vendor=a,other=b", trace.tracestate());
        assertEquals("user=alice", trace.baggage());
        assertEquals("0af7651916cd43dd8448eb211c80319c", trace.traceId());
        assertEquals("00f067aa0ba902b7", trace.parentSpanId());
        assertTrue(trace.sampled());
        assertFalse(TraceContext.from(Json.createObjectBuilder().add("traceparent", "00-0af7651916cd43dd8448eb211c80319c-00f067aa0ba902b7-00").build()).sampled());
    }

    @Test
    void aBrokenValueIsDroppedAndNeverFailsTheRequest() {
        assertSame(TraceContext.EMPTY, TraceContext.from(null));
        assertSame(TraceContext.EMPTY, TraceContext.from(Json.createObjectBuilder().build()));
        assertSame(TraceContext.EMPTY, TraceContext.from(Json.createObjectBuilder().add("traceparent", "garbage").add("tracestate", "a=b").build()));
        assertSame(TraceContext.EMPTY, TraceContext.from(Json.createObjectBuilder().add("traceparent", 5).build()));
        TraceContext onlyBaggage = TraceContext.from(Json.createObjectBuilder().add("traceparent", "garbage").add("baggage", "k=v").add("tracestate", "bad\nvalue").build());
        assertEquals(null, onlyBaggage.traceparent());
        assertEquals(null, onlyBaggage.tracestate());
        assertEquals("k=v", onlyBaggage.baggage());
        assertEquals(null, TraceContext.from(Json.createObjectBuilder().add("traceparent", TP).add("tracestate", "line\nbreak").build()).tracestate());
    }

    @Test
    void aChildKeepsTheTraceAndGetsANewSpan() {
        TraceContext parent = TraceContext.from(Json.createObjectBuilder().add("traceparent", TP).add("tracestate", "v=1").add("baggage", "k=v").build());
        TraceContext child  = parent.child();
        assertEquals(parent.traceId(), child.traceId());
        assertNotEquals(parent.parentSpanId(), child.parentSpanId());
        assertTrue(TraceContext.isValidTraceparent(child.traceparent()));
        assertEquals(parent.sampled(), child.sampled());
        assertEquals("v=1", child.tracestate());
        assertEquals("k=v", child.baggage());
        assertSame(TraceContext.EMPTY, TraceContext.EMPTY.child());
    }

    @Test
    void toJsonHoldsOnlyWhatIsPresent() {
        assertEquals(0, TraceContext.EMPTY.toJson().size());
        JsonObject json = new TraceContext(TP, null, "k=v").toJson();
        assertEquals(2, json.size());
        assertEquals(TP, json.getString("traceparent"));
        assertEquals("k=v", json.getString("baggage"));
    }

    @Test
    void theKeysPassTheMetaKeyCheck() {
        Meta.validate(Json.createObjectBuilder().add("traceparent", TP).add("tracestate", "a=b").add("baggage", "k=v").build(), "test");
    }

    @Test
    void aToolSeesTheTraceOfItsRequest() {
        AtomicReference<TraceContext> seen = new AtomicReference<>();
        McpRequestHandler handler = new McpRequestHandler(McpRegistry.of(List.of(new Capture(seen))), new McpConfig(null, 65536, 1000L, "t", "1", null));
        assertEquals(200, call(handler, Json.createObjectBuilder().add("traceparent", TP).add("baggage", "k=v")).status());
        assertEquals(TP, seen.get().traceparent());
        assertEquals("k=v", seen.get().baggage());
        assertEquals(200, call(handler, Json.createObjectBuilder().add("traceparent", "not-a-traceparent")).status());
        assertTrue(seen.get().isEmpty());
        assertEquals(200, call(handler, Json.createObjectBuilder()).status());
        assertSame(TraceContext.EMPTY, seen.get());
    }

    private static McpHttpResponse call(McpRequestHandler handler, jakarta.json.JsonObjectBuilder extraMeta) {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.put("MCP-Protocol-Version", "2026-07-28");
        headers.put("Mcp-Method", "tools/call");
        headers.put("Mcp-Name", "capture");
        JsonObject meta = extraMeta.add("io.modelcontextprotocol/protocolVersion", "2026-07-28").add("io.modelcontextprotocol/clientCapabilities", Json.createObjectBuilder()).build();
        String body = Json.createObjectBuilder().add("jsonrpc", "2.0").add("id", 1).add("method", "tools/call").add("params", Json.createObjectBuilder().add("name", "capture").add("_meta", meta)).build().toString();
        return handler.handle(headers::get, new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    private record Capture(AtomicReference<TraceContext> seen) implements McpToolHandler {

        @Override
        public String name() {
            return "capture";
        }

        @Override
        public String description() {
            return "remembers the trace context";
        }

        @Override
        public JsonObject inputSchema() {
            return Json.createObjectBuilder().add("type", "object").build();
        }

        @Override
        public ToolResult call(ToolCall call) {
            seen.set(call.trace());
            return ToolResult.text("ok");
        }
    }
}
