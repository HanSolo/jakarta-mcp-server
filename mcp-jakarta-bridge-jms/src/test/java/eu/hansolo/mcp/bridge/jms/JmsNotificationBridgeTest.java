package eu.hansolo.mcp.bridge.jms;

import eu.hansolo.mcp.server.McpChange;
import jakarta.jms.BytesMessage;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSContext;
import jakarta.jms.Topic;
import org.apache.activemq.artemis.core.config.Configuration;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bridge against an embedded Artemis broker in this JVM: two bridges stand for two server instances. */
class JmsNotificationBridgeTest {

    private static final AtomicInteger BROKERS = new AtomicInteger();
    private static final String        TOPIC   = "mcp.test.notifications";

    private final String                        url   = "vm://" + (1000 + BROKERS.incrementAndGet());
    private final List<JmsNotificationBridge>   open  = new CopyOnWriteArrayList<>();
    private EmbeddedActiveMQ                    broker;


    @BeforeEach
    void startBroker() throws Exception {
        broker = newBroker();
        broker.start();
    }

    @AfterEach
    void stop() throws Exception {
        open.forEach(JmsNotificationBridge::close);
        broker.stop();
    }


    @Test
    void aChangePublishedByOneInstanceReachesTheOtherAndTheSenderToo() throws Exception {
        BlockingQueue<McpChange> heardA = new LinkedBlockingQueue<>();
        BlockingQueue<McpChange> heardB = new LinkedBlockingQueue<>();
        JmsNotificationBridge a = listening(heardA);
        JmsNotificationBridge b = listening(heardB);

        McpChange change = new McpChange("instance-a", McpChange.Kind.RESOURCE_UPDATED, "ticket://7");
        a.publish(change);

        assertEquals(change, heardB.poll(10, TimeUnit.SECONDS));
        assertEquals(change, heardA.poll(10, TimeUnit.SECONDS), "the sender hears it too; McpNotifications.receive ignores its own origin");
        assertNull(heardB.poll(300, TimeUnit.MILLISECONDS), "exactly once");
    }

    @Test
    void everyKindOfChangeSurvivesTheTrip() throws Exception {
        BlockingQueue<McpChange> heard = new LinkedBlockingQueue<>();
        JmsNotificationBridge a = listening(new LinkedBlockingQueue<>());
        listening(heard);
        List<McpChange> sent = List.of(
            new McpChange("x", McpChange.Kind.TOOLS_LIST_CHANGED, null),
            new McpChange("x", McpChange.Kind.PROMPTS_LIST_CHANGED, null),
            new McpChange("x", McpChange.Kind.RESOURCES_LIST_CHANGED, null),
            new McpChange("x", McpChange.Kind.RESOURCE_UPDATED, "ticket://\u00e4/\u00fc?x=1"));
        for (McpChange change : sent) {
            a.publish(change);
        }
        List<McpChange> received = new ArrayList<>();
        for (int i = 0; i < sent.size(); i++) {
            received.add(heard.poll(10, TimeUnit.SECONDS));
        }
        assertEquals(sent, received, "in the order they were sent");
    }

    @Test
    void anUnconfiguredBridgeDoesNothing() throws Exception {
        JmsNotificationBridge inert = new JmsNotificationBridge();
        assertFalse(inert.active());
        inert.start();
        inert.publish(new McpChange("x", McpChange.Kind.TOOLS_LIST_CHANGED, null));
        assertFalse(inert.awaitListening(Duration.ofMillis(100)));
        inert.close();
    }

    @Test
    void messagesThatAreNotChangesAreSkippedAndTheNextOneStillArrives() throws Exception {
        BlockingQueue<McpChange> heard = new LinkedBlockingQueue<>();
        JmsNotificationBridge a = listening(heard);
        try (JMSContext context = factory().createContext()) {
            Topic topic = context.createTopic(TOPIC);
            context.createProducer().send(topic, "this is not json");
            context.createProducer().send(topic, "{\"v\":99,\"o\":\"x\",\"k\":\"tools\"}");
            BytesMessage bytes = context.createBytesMessage();
            bytes.writeBytes(new byte[] {1, 2, 3});
            context.createProducer().send(topic, bytes);
        }
        McpChange good = new McpChange("x", McpChange.Kind.PROMPTS_LIST_CHANGED, null);
        a.publish(good);
        assertEquals(good, heard.poll(10, TimeUnit.SECONDS));
        assertNull(heard.poll(300, TimeUnit.MILLISECONDS));
    }

    @Test
    void aSinkThatThrowsDoesNotStopTheListener() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        BlockingQueue<McpChange> heard = new LinkedBlockingQueue<>();
        JmsNotificationBridge a = new JmsNotificationBridge(factory(), TOPIC, change -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("boom");
            }
            heard.add(change);
        }, 60_000L, 100L);
        open.add(a);
        a.start();
        assertTrue(a.awaitListening(Duration.ofSeconds(10)));
        a.publish(new McpChange("x", McpChange.Kind.TOOLS_LIST_CHANGED, null));
        a.publish(new McpChange("x", McpChange.Kind.PROMPTS_LIST_CHANGED, null));
        assertEquals(McpChange.Kind.PROMPTS_LIST_CHANGED, heard.poll(10, TimeUnit.SECONDS).kind());
    }

    @Test
    void publishingWhileTheBrokerIsDownFailsAndWorksAgainWhenItIsBack() throws Exception {
        BlockingQueue<McpChange> heard = new LinkedBlockingQueue<>();
        JmsNotificationBridge a = listening(heard);
        a.publish(new McpChange("x", McpChange.Kind.TOOLS_LIST_CHANGED, null));
        assertNotNull(heard.poll(10, TimeUnit.SECONDS));

        broker.stop();
        assertThrows(RuntimeException.class, () -> a.publish(new McpChange("x", McpChange.Kind.TOOLS_LIST_CHANGED, null)));

        broker = newBroker();
        broker.start();
        McpChange after = new McpChange("x", McpChange.Kind.RESOURCES_LIST_CHANGED, null);
        // the listener reconnects on its own schedule (100 ms here); keep publishing until a change arrives
        McpChange received = null;
        long deadline = System.currentTimeMillis() + 30_000;
        while (received == null && System.currentTimeMillis() < deadline) {
            try {
                a.publish(after);
            } catch (RuntimeException stillDown) {
                // the next attempt connects again
            }
            received = heard.poll(500, TimeUnit.MILLISECONDS);
        }
        assertEquals(after, received);
    }

    @Test
    void closeStopsTheListenerAndLaterPublishesAreIgnored() throws Exception {
        BlockingQueue<McpChange> heard = new LinkedBlockingQueue<>();
        JmsNotificationBridge a = listening(heard);
        a.close();
        a.publish(new McpChange("x", McpChange.Kind.TOOLS_LIST_CHANGED, null));
        assertNull(heard.poll(500, TimeUnit.MILLISECONDS));
    }


    // ---- helpers ---------------------------------------------------------------------------------------------------

    private JmsNotificationBridge listening(BlockingQueue<McpChange> into) throws Exception {
        JmsNotificationBridge bridge = new JmsNotificationBridge(factory(), TOPIC, into::add, 60_000L, 100L);
        open.add(bridge);
        bridge.start();
        assertTrue(bridge.awaitListening(Duration.ofSeconds(10)), "the listener did not subscribe in time");
        return bridge;
    }

    private ConnectionFactory factory() {
        return new ActiveMQConnectionFactory(url);
    }

    private EmbeddedActiveMQ newBroker() throws Exception {
        Configuration configuration = new ConfigurationImpl()
            .setPersistenceEnabled(false)
            .setSecurityEnabled(false)
            .setJMXManagementEnabled(false)
            .addAcceptorConfiguration("in-vm", url);
        return new EmbeddedActiveMQ().setConfiguration(configuration);
    }
}
