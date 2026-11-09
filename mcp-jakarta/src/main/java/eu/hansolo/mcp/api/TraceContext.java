package eu.hansolo.mcp.api;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonString;

import java.security.SecureRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The OpenTelemetry trace context a client put in the {@code _meta} of a request: {@code traceparent}, {@code tracestate} and
 * {@code baggage} (W3C Trace Context and W3C Baggage). These three keys are the one exception to the {@code prefix/name} rule for
 * {@code _meta} keys.
 *
 * <p>The library only reads and hands the values on; it starts no spans and needs no OpenTelemetry dependency. A handler passes
 * {@link #traceparent()} to its tracer as the remote parent, or calls {@link #child()} and {@link #toJson()} to forward the context in
 * the {@code _meta} of a request it makes itself.
 *
 * <p>A value that does not follow the W3C format is dropped, as the W3C recommendation says for a malformed {@code traceparent}:
 * a broken tracing header never fails the request. {@code tracestate} and {@code baggage} are only checked for being printable ASCII
 * (a lenient check, not a full parse), and a {@code tracestate} is dropped together with an invalid or missing {@code traceparent},
 * because it has no meaning without one.
 *
 * @param traceparent {@code version-traceid-parentid-flags}, lower-case hex, or null
 * @param tracestate  vendor state that goes with the traceparent, or null
 * @param baggage     W3C baggage, or null
 */
public record TraceContext(String traceparent, String tracestate, String baggage) {

    /** No trace context. */
    public static final TraceContext EMPTY = new TraceContext(null, null, null);

    private static final Pattern TRACEPARENT = Pattern.compile("([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})(-.*)?");
    private static final Pattern PRINTABLE   = Pattern.compile("[\\x20-\\x7E]+");
    private static final SecureRandom RANDOM = new SecureRandom();


    /** @return the valid parts of the {@code _meta} object; {@link #EMPTY} if there are none (or {@code meta} is null) */
    public static TraceContext from(JsonObject meta) {
        if (meta == null) {
            return EMPTY;
        }
        String parent = isValidTraceparent(string(meta, "traceparent")) ? string(meta, "traceparent") : null;
        String state  = parent != null && isPrintable(string(meta, "tracestate")) ? string(meta, "tracestate") : null;
        String bag    = isPrintable(string(meta, "baggage")) ? string(meta, "baggage") : null;
        return parent == null && state == null && bag == null ? EMPTY : new TraceContext(parent, state, bag);
    }

    /** True for a W3C {@code traceparent}: version (not ff), 32-hex trace id and 16-hex parent id (neither all zero), 2-hex flags; version 00 allows nothing more. */
    public static boolean isValidTraceparent(String value) {
        if (value == null) {
            return false;
        }
        Matcher m = TRACEPARENT.matcher(value);
        if (!m.matches() || m.group(1).equals("ff")) {
            return false;
        }
        if (m.group(1).equals("00") && m.group(5) != null) {
            return false;
        }
        return !m.group(2).chars().allMatch(c -> c == '0') && !m.group(3).chars().allMatch(c -> c == '0');
    }


    public boolean isEmpty() {
        return traceparent == null && tracestate == null && baggage == null;
    }

    /** The 32-hex trace id, or null without a traceparent. */
    public String traceId() {
        return traceparent == null ? null : traceparent.substring(3, 35);
    }

    /** The 16-hex id of the caller's span (the remote parent), or null without a traceparent. */
    public String parentSpanId() {
        return traceparent == null ? null : traceparent.substring(36, 52);
    }

    /** True if the caller's trace is sampled (flag bit 0), false if not or if there is no traceparent. */
    public boolean sampled() {
        return traceparent != null && (Integer.parseInt(traceparent.substring(53, 55), 16) & 1) == 1;
    }

    /**
     * The context to forward on a request made while handling this one: same trace id, tracestate, baggage and flags, a new random
     * span id. A handler that traces with its own library should use that library's span id instead.
     */
    public TraceContext child() {
        if (traceparent == null) {
            return this;
        }
        byte[] id = new byte[8];
        do {
            RANDOM.nextBytes(id);
        } while (allZero(id));
        StringBuilder span = new StringBuilder(16);
        for (byte b : id) {
            span.append(String.format("%02x", b));
        }
        return new TraceContext(traceparent.substring(0, 36) + span + traceparent.substring(52), tracestate, baggage);
    }

    /** The parts that are present, as {@code _meta} entries (an empty object for {@link #EMPTY}), ready to merge into the {@code _meta} of an outgoing request. */
    public JsonObject toJson() {
        JsonObjectBuilder json = Json.createObjectBuilder();
        if (traceparent != null) {
            json.add("traceparent", traceparent);
        }
        if (tracestate != null) {
            json.add("tracestate", tracestate);
        }
        if (baggage != null) {
            json.add("baggage", baggage);
        }
        return json.build();
    }


    private static String string(JsonObject meta, String key) {
        return meta.get(key) instanceof JsonString s ? s.getString() : null;
    }

    private static boolean isPrintable(String value) {
        return value != null && PRINTABLE.matcher(value).matches();
    }

    private static boolean allZero(byte[] bytes) {
        for (byte b : bytes) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }
}
