package eu.hansolo.mcp.server;

/**
 * Carries the changes announced to {@link McpNotifications} to the other server instances, so that one call reaches the clients
 * listening on every instance. The library has no messaging of its own; implement this on top of what the application already uses
 * (a JMS topic, database notifications, a cache event, ...) and make it a CDI bean:
 *
 * <pre>{@code
 * @ApplicationScoped
 * public class TopicBridge implements McpNotificationBridge {
 *
 *     @Inject McpNotifications notifications;
 *
 *     @Override
 *     public void publish(McpChange change) {
 *         topic.send(change.encode());                         // to the other instances
 *     }
 *
 *     void onMessage(String text) {                            // called by the topic on every instance
 *         notifications.receive(McpChange.decode(text));       // to the clients of this instance
 *     }
 * }
 * }</pre>
 *
 * <p>How it works:
 * <ul>
 *   <li>{@link McpNotifications#toolsListChanged()} and its siblings tell the local clients at once and then pass the change to
 *       {@link #publish}. The call does not wait for the bridge: a separate thread hands the changes over, one at a time and in order.</li>
 *   <li>{@link McpNotifications#receive(McpChange)} tells the local clients only. It never publishes again, so a change does not
 *       travel in circles, and it ignores a change that came from this instance, so a topic that echoes to its sender is fine.</li>
 *   <li>Registration is not part of this: {@link McpRegistrations} changes the registry of its own instance only, so make the same
 *       call on every instance. It tells the local clients and does not publish.</li>
 * </ul>
 *
 * <p>Delivery is as good as the transport behind it. A change published while another instance is down or disconnected is lost
 * there, as is any change when the hand-over queue (1000 changes) overflows because {@code publish} stays slow; clients that
 * listen read the lists again after they reconnect.
 */
@FunctionalInterface
public interface McpNotificationBridge {

    /**
     * Sends the change to the other instances. It is called on the library's own thread, never on a request thread, so it may block
     * for a while. An exception is logged and the change is dropped; the next change is still passed on.
     *
     * <p>Do not deliver it to this instance: the clients here were told already. If the transport cannot avoid that, nothing is lost:
     * {@link McpNotifications#receive(McpChange)} ignores changes that carry this instance's {@link McpNotifications#instanceId()}.
     */
    void publish(McpChange change) throws Exception;
}
