package eu.hansolo.mcp.server;

import jakarta.json.Json;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonValue;
import eu.hansolo.mcp.protocol.MetaKeys;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One open {@code subscriptions/listen} stream. Changes are kept as pending state rather than as a queue: "the tool list changed"
 * twice before the stream gets to write is still one notification, and the pending resource URIs are capped, so a slow client
 * cannot make the server buffer without bound. A stream that falls too far behind is closed and the client resynchronizes.
 *
 * <p>Waiting uses a lock and a condition rather than {@code synchronized} and {@code wait()}: a virtual thread that blocks in a
 * monitor pins its carrier thread on JDK 21 (fixed in JDK 24), and the streams may run on virtual threads.
 */
final class Subscription {

    static final int MAX_PENDING_URIS = 1000;

    private final JsonValue   id;
    private final String      owner;
    private final Instant     expiresAt;
    private final boolean     tools;
    private final boolean     prompts;
    private final boolean     resources;
    private final Set<String> uris;

    private final Set<String> pendingUris = new LinkedHashSet<>();
    private boolean           pendingTools;
    private boolean           pendingPrompts;
    private boolean           pendingResources;
    private boolean           closed;

    private final ReentrantLock lock    = new ReentrantLock();
    private final Condition     changed = lock.newCondition();


    /**
     * @param id        the JSON-RPC id of the {@code subscriptions/listen} request, which is also the subscription id
     * @param owner     who opened it (for the per-caller limit)
     * @param expiresAt when the caller's token expires, or null; the stream ends then
     * @param uris      the resource URIs the server agreed to watch
     */
    Subscription(JsonValue id, String owner, Instant expiresAt, boolean tools, boolean prompts, boolean resources, Set<String> uris) {
        this.id        = id;
        this.owner     = owner;
        this.expiresAt = expiresAt;
        this.tools     = tools;
        this.prompts   = prompts;
        this.resources = resources;
        this.uris      = Set.copyOf(uris);
    }


    JsonValue id() {
        return id;
    }

    String owner() {
        return owner;
    }

    Instant expiresAt() {
        return expiresAt;
    }

    /** The filter the server honors, as sent in the acknowledgement. */
    JsonObjectBuilder honoredFilter() {
        JsonObjectBuilder filter = Json.createObjectBuilder();
        if (tools) {
            filter.add("toolsListChanged", true);
        }
        if (prompts) {
            filter.add("promptsListChanged", true);
        }
        if (resources) {
            filter.add("resourcesListChanged", true);
        }
        if (!uris.isEmpty()) {
            var watched = Json.createArrayBuilder();
            uris.forEach(watched::add);
            filter.add("resourceSubscriptions", watched);
        }
        return filter;
    }

    void toolsListChanged() {
        lock.lock();
        try {
            if (tools && !closed) {
                pendingTools = true;
                changed.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    void promptsListChanged() {
        lock.lock();
        try {
            if (prompts && !closed) {
                pendingPrompts = true;
                changed.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    void resourcesListChanged() {
        lock.lock();
        try {
            if (resources && !closed) {
                pendingResources = true;
                changed.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    void resourceUpdated(String uri) {
        lock.lock();
        try {
            if (closed || !uris.contains(uri)) {
                return;
            }
            if (!pendingUris.contains(uri) && pendingUris.size() >= MAX_PENDING_URIS) {
                closed = true;   // too far behind: end the stream, the client reconnects and rereads
            } else {
                pendingUris.add(uri);
            }
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** Ends the stream gracefully; the writer sends the final response. */
    void close() {
        lock.lock();
        try {
            closed = true;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    boolean isClosed() {
        lock.lock();
        try {
            return closed;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Waits up to {@code maxMillis} for something to send.
     *
     * @return the notifications to write now (JSON texts), empty on timeout or when the stream was closed
     */
    List<String> await(long maxMillis) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            long left = TimeUnit.MILLISECONDS.toNanos(Math.max(0L, maxMillis));
            while (!hasPending() && !closed && left > 0) {
                left = changed.awaitNanos(left);
            }
            List<String> out = new ArrayList<>();
            if (pendingTools) {
                out.add(notification("notifications/tools/list_changed", null));
            }
            if (pendingPrompts) {
                out.add(notification("notifications/prompts/list_changed", null));
            }
            if (pendingResources) {
                out.add(notification("notifications/resources/list_changed", null));
            }
            for (String uri : pendingUris) {
                out.add(notification("notifications/resources/updated", uri));
            }
            pendingTools     = false;
            pendingPrompts   = false;
            pendingResources = false;
            pendingUris.clear();
            return out;
        } finally {
            lock.unlock();
        }
    }

    private boolean hasPending() {
        return pendingTools || pendingPrompts || pendingResources || !pendingUris.isEmpty();
    }

    String acknowledgement() {
        return notificationWith("notifications/subscriptions/acknowledged", Json.createObjectBuilder().add("notifications", honoredFilter()));
    }

    private String notification(String method, String uri) {
        JsonObjectBuilder params = Json.createObjectBuilder();
        if (uri != null) {
            params.add("uri", uri);
        }
        return notificationWith(method, params);
    }

    private String notificationWith(String method, JsonObjectBuilder params) {
        params.add("_meta", Json.createObjectBuilder().add(MetaKeys.SUBSCRIPTION_ID, id));
        return Json.createObjectBuilder().add("jsonrpc", "2.0").add("method", method).add("params", params).build().toString();
    }

    /** The response that ends a stream the server closes (token expiry, shutdown, falling behind). */
    String finalResponse() {
        return Json.createObjectBuilder().add("jsonrpc", "2.0").add("id", id).add("result", Json.createObjectBuilder().add("resultType", "complete").add("_meta", Json.createObjectBuilder().add(MetaKeys.SUBSCRIPTION_ID, id))).build().toString();
    }
}
