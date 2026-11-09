package eu.hansolo.mcp.server;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.json.JsonValue;
import eu.hansolo.mcp.protocol.ErrorCodes;
import eu.hansolo.mcp.protocol.McpProtocolException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

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
 * <p><b>This is per server instance.</b> A stream lives on the instance that accepted it, so the clients of the other instances
 * are told only if the change reaches them. With several instances, provide a {@link McpNotificationBridge}: it passes every
 * change announced here on to the other instances (a message topic, a database notification, ...), which tell their clients with
 * {@link #receive(McpChange)}. Without a bridge, announce the change on every instance yourself.
 */
@Singleton
public class McpNotifications {

    private static final Logger LOG             = Logger.getLogger(McpNotifications.class.getName());
    private static final int    OUTBOX_CAPACITY = 1000;

    private final Set<Subscription>                         subscriptions = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer>                      perOwner      = new ConcurrentHashMap<>();
    private final int                                       maxListeners;
    private final int                                       maxPerCaller;
    private final String                                    instanceId    = UUID.randomUUID().toString();
    private final Supplier<Iterable<McpNotificationBridge>> bridgeSource;
    private final BlockingQueue<McpChange>                  outbox        = new ArrayBlockingQueue<>(OUTBOX_CAPACITY);
    private final AtomicLong                                dropped       = new AtomicLong();
    private volatile List<McpNotificationBridge>            bridges;
    private Thread                                          publisher;
    private volatile boolean                                shutdown;


    @Inject
    public McpNotifications(McpConfig config, Instance<McpNotificationBridge> available) {
        this(config, () -> available);
    }

    /** Without a bridge: changes reach the clients of this instance only. */
    public McpNotifications(McpConfig config) {
        this(config, List::of);
    }

    McpNotifications(McpConfig config, Supplier<Iterable<McpNotificationBridge>> bridgeSource) {
        this.maxListeners = config.maxListeners();
        this.maxPerCaller = config.maxListenersPerCaller();
        this.bridgeSource = bridgeSource;
    }


    /** The tool list changed (a tool was added, removed or became available to other callers); subscribed clients should call tools/list again. */
    public void toolsListChanged() {
        toolsListChangedLocally();
        forward(McpChange.Kind.TOOLS_LIST_CHANGED, null);
    }

    public void promptsListChanged() {
        promptsListChangedLocally();
        forward(McpChange.Kind.PROMPTS_LIST_CHANGED, null);
    }

    public void resourcesListChanged() {
        resourcesListChangedLocally();
        forward(McpChange.Kind.RESOURCES_LIST_CHANGED, null);
    }

    /** The content of this resource changed. Only streams that subscribed to exactly this URI, and may read it, are told. */
    public void resourceUpdated(String uri) {
        if (uri != null && !uri.isBlank()) {
            resourceUpdatedLocally(uri);
            forward(McpChange.Kind.RESOURCE_UPDATED, uri);
        }
    }

    /**
     * Tells the clients of this instance about a change announced on another instance, normally from a {@link McpNotificationBridge}.
     * Never passes the change on, and ignores one that came from this instance itself.
     */
    public void receive(McpChange change) {
        Objects.requireNonNull(change, "change");
        if (change.origin().equals(instanceId)) {
            return;
        }
        switch (change.kind()) {
            case TOOLS_LIST_CHANGED     -> toolsListChangedLocally();
            case PROMPTS_LIST_CHANGED   -> promptsListChangedLocally();
            case RESOURCES_LIST_CHANGED -> resourcesListChangedLocally();
            case RESOURCE_UPDATED       -> resourceUpdatedLocally(change.uri());
        }
    }

    /** Names this instance in the changes it publishes, so that {@link #receive(McpChange)} can recognize its own. New on every start. */
    public String instanceId() {
        return instanceId;
    }

    /** How many streams are open on this instance. */
    public int activeSubscriptions() {
        return subscriptions.size();
    }

    /** A task changed on this instance; streams that watch it look at it now instead of at their next poll. */
    void taskChanged(String taskId) {
        subscriptions.forEach(s -> s.taskChanged(taskId));
    }

    /** For {@link McpRegistrations}: the registry is per instance, so the other instances are not told. */
    void toolsListChangedLocally() {
        subscriptions.forEach(Subscription::toolsListChanged);
    }

    void promptsListChangedLocally() {
        subscriptions.forEach(Subscription::promptsListChanged);
    }

    void resourcesListChangedLocally() {
        subscriptions.forEach(Subscription::resourcesListChanged);
    }

    void resourceUpdatedLocally(String uri) {
        subscriptions.forEach(s -> s.resourceUpdated(uri));
    }

    /** Queues the change for the bridges. Never blocks, and costs one list lookup when there is no bridge. */
    private void forward(McpChange.Kind kind, String uri) {
        if (shutdown || bridges().isEmpty()) {
            return;
        }
        McpChange change = new McpChange(instanceId, kind, uri);
        if (outbox.contains(change)) {
            return;   // the same change is still waiting: one message tells the other instances as well as two
        }
        if (!outbox.offer(change)) {
            long total = dropped.incrementAndGet();
            if (total % 100 == 1) {
                LOG.warning("The notification bridge cannot keep up; " + total + " change(s) were not passed on to the other instances so far");
            }
            return;
        }
        startPublisher();
    }

    private List<McpNotificationBridge> bridges() {
        List<McpNotificationBridge> found = bridges;
        if (found == null) {
            synchronized (this) {
                found = bridges;
                if (found == null) {
                    try {
                        List<McpNotificationBridge> all = new ArrayList<>();
                        bridgeSource.get().forEach(all::add);
                        found = bridges = List.copyOf(all);   // looked up on first use, because a bridge usually injects this bean
                    } catch (RuntimeException e) {
                        LOG.log(Level.WARNING, "Could not look up the notification bridges; changes reach the clients of this instance only", e);
                        return List.of();
                    }
                }
            }
        }
        return found;
    }

    private synchronized void startPublisher() {
        if (publisher == null && !shutdown) {
            publisher = Thread.ofPlatform().daemon().name("mcp-notification-bridge").start(this::publishQueued);
        }
    }

    private void publishQueued() {
        try {
            while (!shutdown) {
                McpChange change = outbox.take();
                for (McpNotificationBridge bridge : bridges()) {
                    try {
                        bridge.publish(change);
                    } catch (Exception e) {
                        LOG.log(Level.WARNING, "The notification bridge " + bridge.getClass().getName() + " could not publish a " + change.kind() + " change", e);
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
            if (publisher != null) {
                publisher.interrupt();   // changes still waiting for the bridge are dropped
            }
        }
        subscriptions.forEach(Subscription::close);
    }

    static Instant expiry(JsonValue exp) {
        return exp instanceof jakarta.json.JsonNumber number ? Instant.ofEpochSecond(number.longValue()) : null;
    }
}
