package eu.hansolo.mcp.server;

import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.json.JsonValue;
import eu.hansolo.mcp.protocol.ErrorCodes;
import eu.hansolo.mcp.protocol.McpProtocolException;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tells clients that hold a {@code subscriptions/listen} stream that something changed. Inject it where the change happens:
 *
 * <pre>{@code
 * @Inject McpNotifications notifications;
 * ...
 * ticketStore.close(id);
 * notifications.resourceUpdated("ticket://" + id);
 * }</pre>
 *
 * <p>The server cannot see on its own when a resource changed or when the set of tools, prompts or resources visible to
 * clients changed (the registry is built from CDI beans at startup), so the application says so. Calls are cheap and never
 * block: they only mark state on the open streams, each of which then sends one notification.
 *
 * <p><b>This is per server instance.</b> A stream lives on the instance that accepted it. With several instances, publish the
 * same event on every instance (a message topic, a database notification, ...) and call these methods from that listener.
 */
@Singleton
public class McpNotifications {

    private final Set<Subscription>        subscriptions = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer>     perOwner      = new ConcurrentHashMap<>();
    private final int                      maxListeners;
    private final int                      maxPerCaller;
    private volatile boolean               shutdown;


    @Inject
    public McpNotifications(McpConfig config) {
        this.maxListeners = config.maxListeners();
        this.maxPerCaller = config.maxListenersPerCaller();
    }


    /** The tool list changed (a tool was added, removed or became available to other callers); subscribed clients should call tools/list again. */
    public void toolsListChanged() {
        subscriptions.forEach(Subscription::toolsListChanged);
    }

    public void promptsListChanged() {
        subscriptions.forEach(Subscription::promptsListChanged);
    }

    public void resourcesListChanged() {
        subscriptions.forEach(Subscription::resourcesListChanged);
    }

    /** The content of this resource changed. Only streams that subscribed to exactly this URI, and may read it, are told. */
    public void resourceUpdated(String uri) {
        if (uri != null && !uri.isBlank()) {
            subscriptions.forEach(s -> s.resourceUpdated(uri));
        }
    }

    /** How many streams are open on this instance. */
    public int activeSubscriptions() {
        return subscriptions.size();
    }

    /**
     * Registers a stream.
     *
     * @throws McpProtocolException (HTTP 503) if the server or this caller already holds the maximum number of streams
     */
    Subscription open(Subscription subscription) {
        synchronized (this) {
            if (shutdown) {
                throw new McpProtocolException(503, ErrorCodes.INTERNAL_ERROR, "The server is shutting down", null);
            }
            if (subscriptions.size() >= maxListeners) {
                throw new McpProtocolException(503, ErrorCodes.INTERNAL_ERROR, "Too many open subscriptions; try again later", null);
            }
            if (perOwner.getOrDefault(subscription.owner(), 0) >= maxPerCaller) {
                throw new McpProtocolException(503, ErrorCodes.INTERNAL_ERROR, "This caller already holds the maximum number of subscriptions", null);
            }
            perOwner.merge(subscription.owner(), 1, Integer::sum);
            subscriptions.add(subscription);
        }
        return subscription;
    }

    void remove(Subscription subscription) {
        synchronized (this) {
            if (subscriptions.remove(subscription)) {
                perOwner.computeIfPresent(subscription.owner(), (owner, count) -> count <= 1 ? null : count - 1);
            }
        }
    }

    /** Ends every open stream gracefully when the application stops. */
    @PreDestroy
    public void close() {
        synchronized (this) {
            shutdown = true;
        }
        subscriptions.forEach(Subscription::close);
    }

    static Instant expiry(JsonValue exp) {
        return exp instanceof jakarta.json.JsonNumber number ? Instant.ofEpochSecond(number.longValue()) : null;
    }
}
