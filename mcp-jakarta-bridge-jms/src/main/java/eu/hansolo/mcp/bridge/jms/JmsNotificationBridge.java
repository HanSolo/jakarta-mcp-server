package eu.hansolo.mcp.bridge.jms;

import eu.hansolo.mcp.server.McpChange;
import eu.hansolo.mcp.server.McpNotificationBridge;
import eu.hansolo.mcp.server.McpNotifications;
import eu.hansolo.mcp.server.Settings;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.DeliveryMode;
import jakarta.jms.JMSConsumer;
import jakarta.jms.JMSContext;
import jakarta.jms.JMSException;
import jakarta.jms.JMSProducer;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;
import jakarta.jms.Topic;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;


/**
 * A {@link McpNotificationBridge} on Jakarta Messaging: every change announced on one server instance is sent to a topic, and every
 * instance listens to that topic and tells its own clients. Put this module's jar in the application and set
 * {@code mcp.bridge.jms.connection-factory} to the JNDI name of a {@code jakarta.jms.ConnectionFactory}; without that setting the bean
 * does nothing, so the jar is harmless where the bridge is not wanted.
 *
 * <p>Settings (system property, or the environment variable with the name upper-cased and {@code . -} replaced by {@code _}):
 * <ul>
 *   <li>{@code mcp.bridge.jms.connection-factory}: JNDI name of the connection factory (switches the bridge on).</li>
 *   <li>{@code mcp.bridge.jms.topic}: the topic name, default {@code mcp.notifications}. It is created through
 *       {@code JMSContext.createTopic}, so the broker needs no administered object for it (most brokers create the address on use).</li>
 *   <li>{@code mcp.bridge.jms.user}, {@code mcp.bridge.jms.password}: credentials for the connection, if the broker needs them.</li>
 *   <li>{@code mcp.bridge.jms.ttl-ms}: how long a message lives, default 60000. Changes are not worth delivering late.</li>
 *   <li>{@code mcp.bridge.jms.reconnect-ms}: pause between attempts to connect the listener again, default 5000.</li>
 * </ul>
 *
 * <p>How it behaves:
 * <ul>
 *   <li>Messages are non-persistent text messages (the JSON of {@link McpChange#encode()}). Each instance listens with its own
 *       non-durable subscription, so a change made while an instance is down or disconnected is lost there; its clients read the
 *       lists again after they reconnect, as for every bridge.</li>
 *   <li>The sender hears its own message too. {@link McpNotifications#receive(McpChange)} ignores changes of its own instance, so
 *       nothing is told twice.</li>
 *   <li>The listener polls with {@code receive}, in a thread of its own, instead of using a {@code MessageListener}: Jakarta EE
 *       containers do not allow application code to set one on a container-managed connection factory.</li>
 *   <li>If the connection fails, the listener reconnects every {@code reconnect-ms}. A {@link #publish} that cannot reach the broker
 *       throws (the library logs it and drops that change) and the next one connects again.</li>
 *   <li>A message that is not a text message or not a change of this version is ignored and logged (the first one, then every 100th).</li>
 * </ul>
 *
 * <p><b>Secure the topic.</b> Whoever can publish to it can make every instance send {@code list_changed} and {@code resources/updated}
 * notifications to its clients (and nothing else: a message carries no data a client acts on). Give the broker account of the servers
 * the only rights on the topic.
 */
@ApplicationScoped
public class JmsNotificationBridge implements McpNotificationBridge, AutoCloseable {

    static final String DEFAULT_TOPIC = "mcp.notifications";

    private static final Logger LOG = Logger.getLogger(JmsNotificationBridge.class.getName());

    @Inject
    McpNotifications notifications;

    private final String               jndiName;
    private final String               topicName;
    private final String               user;
    private final String               password;
    private final long                 ttlMs;
    private final long                 reconnectMs;
    private final Consumer<McpChange>  sink;
    private final CountDownLatch       listening = new CountDownLatch(1);
    private final AtomicLong           ignored   = new AtomicLong();

    private volatile ConnectionFactory factory;
    private volatile Thread            receiver;
    private volatile boolean           closed;

    private JMSContext                 producerContext;
    private JMSProducer                producer;
    private Topic                      producerTopic;


    /** For the container: reads the settings listed in the class documentation. */
    public JmsNotificationBridge() {
        this.jndiName    = Settings.get("mcp.bridge.jms.connection-factory");
        this.topicName   = Settings.get("mcp.bridge.jms.topic") != null ? Settings.get("mcp.bridge.jms.topic") : DEFAULT_TOPIC;
        this.user        = Settings.get("mcp.bridge.jms.user");
        this.password    = Settings.get("mcp.bridge.jms.password");
        this.ttlMs       = Math.max(0, Settings.getLong("mcp.bridge.jms.ttl-ms", 60_000L));
        this.reconnectMs = Math.max(10, Settings.getLong("mcp.bridge.jms.reconnect-ms", 5_000L));
        this.factory     = null;
        this.sink        = null;
    }

    /**
     * For code that has its own connection factory (and for tests): call {@link #start()} to begin listening, {@link #close()} to stop.
     * The changes that arrive go to {@code sink}, normally {@code notifications::receive}.
     */
    public JmsNotificationBridge(ConnectionFactory factory, String topic, Consumer<McpChange> sink) {
        this(factory, topic, sink, 60_000L, 5_000L);
    }

    public JmsNotificationBridge(ConnectionFactory factory, String topic, Consumer<McpChange> sink, long ttlMs, long reconnectMs) {
        this.factory     = Objects.requireNonNull(factory, "factory");
        this.topicName   = Objects.requireNonNull(topic, "topic");
        this.sink        = Objects.requireNonNull(sink, "sink");
        this.jndiName    = null;
        this.user        = null;
        this.password    = null;
        this.ttlMs       = ttlMs;
        this.reconnectMs = reconnectMs;
    }


    /** True if a connection factory is known or named; without one this bridge does nothing. */
    public boolean active() {
        return factory != null || jndiName != null;
    }

    /** Called by the container when the application is up: starts listening (if configured), without waiting for the broker. */
    void onStart(@Observes @Initialized(ApplicationScoped.class) Object event) {
        if (active()) {
            LOG.info("The JMS notification bridge listens on topic '" + topicName + "'");
            start();
        } else {
            LOG.fine("The JMS notification bridge is on the classpath but mcp.bridge.jms.connection-factory is not set; it does nothing");
        }
    }

    /** Starts the listener thread. Returns at once, whether or not the broker can be reached. Calling it again does nothing. */
    public synchronized void start() {
        if (!active() || receiver != null || closed) {
            return;
        }
        receiver = Thread.ofPlatform().daemon().name("mcp-bridge-jms").start(this::receiveLoop);
    }

    /** Waits until the listener holds its subscription for the first time. Messages published before that are not seen by this instance. */
    public boolean awaitListening(Duration timeout) throws InterruptedException {
        return listening.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void publish(McpChange change) throws Exception {
        if (!active() || closed) {
            return;
        }
        try {
            if (producerContext == null) {
                JMSContext context = connect();
                producerContext = context;
                producerTopic   = context.createTopic(topicName);
                producer        = context.createProducer().setDeliveryMode(DeliveryMode.NON_PERSISTENT).setTimeToLive(ttlMs);
            }
            producer.send(producerTopic, change.encode());
        } catch (RuntimeException e) {
            closeProducer();      // connect again with the next change
            throw e;
        }
    }

    @PreDestroy
    @Override
    public void close() {
        closed = true;
        Thread thread = receiver;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (this) {
            closeProducer();
        }
    }


    // ---- receiving -------------------------------------------------------------------------------------------------

    private void receiveLoop() {
        boolean failedBefore = false;
        while (!closed) {
            AtomicBoolean broken = new AtomicBoolean();
            try (JMSContext context = connect()) {
                try {
                    context.setExceptionListener(e -> broken.set(true));
                } catch (RuntimeException e) {
                    // a container-managed connection does not allow it; the receive below fails when the connection does
                }
                JMSConsumer consumer = context.createConsumer(context.createTopic(topicName));
                listening.countDown();
                if (failedBefore) {
                    LOG.info("The JMS notification bridge is connected again");
                    failedBefore = false;
                }
                while (!closed && !broken.get()) {
                    Message message = consumer.receive(1_000);
                    if (message != null) {
                        deliver(message);
                    }
                }
            } catch (RuntimeException e) {
                if (closed) {
                    break;
                }
                if (failedBefore) {
                    LOG.warning("The JMS notification bridge is still not connected: " + e);
                } else {
                    LOG.log(Level.WARNING, "The JMS notification bridge lost its connection; changes made on other instances are not heard until it is back", e);
                }
                failedBefore = true;
            }
            if (!closed) {
                try {
                    Thread.sleep(reconnectMs);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
    }

    private void deliver(Message message) {
        String text;
        try {
            if (!(message instanceof TextMessage textMessage)) {
                ignore("it is not a text message");
                return;
            }
            text = textMessage.getText();
        } catch (JMSException e) {
            ignore("it cannot be read: " + e.getMessage());
            return;
        }
        McpChange change;
        try {
            change = McpChange.decode(text);
        } catch (RuntimeException e) {
            ignore("it is not a change message");
            return;
        }
        try {
            (sink != null ? sink : (Consumer<McpChange>) notifications::receive).accept(change);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "A change from the JMS topic could not be passed to the clients of this instance", e);
        }
    }

    private void ignore(String why) {
        long total = ignored.incrementAndGet();
        if (total % 100 == 1) {
            LOG.warning("A message on JMS topic '" + topicName + "' was ignored because " + why + " (" + total + " ignored so far)");
        }
    }


    // ---- connecting ------------------------------------------------------------------------------------------------

    private JMSContext connect() {
        ConnectionFactory connectionFactory = factory();
        return user == null ? connectionFactory.createContext() : connectionFactory.createContext(user, password);
    }

    private ConnectionFactory factory() {
        ConnectionFactory found = factory;
        if (found == null) {
            InitialContext naming = null;
            try {
                naming = new InitialContext();
                found = (ConnectionFactory) naming.lookup(jndiName);
            } catch (NamingException | ClassCastException e) {
                throw new IllegalStateException("The JMS connection factory '" + jndiName + "' cannot be looked up: " + e.getMessage(), e);
            } finally {
                if (naming != null) {
                    try {
                        naming.close();
                    } catch (NamingException ignoredAgain) {
                        // nothing to do
                    }
                }
            }
            factory = found;
        }
        return found;
    }

    private void closeProducer() {
        JMSContext context = producerContext;
        producerContext = null;
        producer        = null;
        producerTopic   = null;
        if (context != null) {
            try {
                context.close();
            } catch (RuntimeException e) {
                LOG.log(Level.FINE, "Closing the JMS producer failed", e);
            }
        }
    }
}
