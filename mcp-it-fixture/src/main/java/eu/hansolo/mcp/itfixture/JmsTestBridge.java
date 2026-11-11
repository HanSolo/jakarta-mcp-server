package eu.hansolo.mcp.itfixture;

import eu.hansolo.mcp.bridge.jms.JmsNotificationBridge;
import eu.hansolo.mcp.server.McpChange;
import eu.hansolo.mcp.server.McpNotificationBridge;
import eu.hansolo.mcp.server.McpNotifications;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.jms.ConnectionFactory;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;

import java.time.Duration;

/**
 * Carries changes over an Artemis topic with the library's {@link JmsNotificationBridge}. The broker comes from the environment
 * variable {@code MCP_IT_JMS_URL} (for example {@code tcp://artemis:61616}), with {@code MCP_IT_JMS_USER} and {@code MCP_IT_JMS_PASSWORD}
 * if the broker needs them. With the variable unset this bean does nothing.
 *
 * <p>The connection factory is the Artemis client's own, created here, so the test needs no JMS setup in the three servers. The
 * library's bean that reads {@code mcp.bridge.jms.connection-factory} (a JNDI name) is in the WAR too and stays idle.
 */
@ApplicationScoped
public class JmsTestBridge implements McpNotificationBridge {

    @Inject McpNotifications notifications;

    private volatile JmsNotificationBridge delegate;


    void onStart(@Observes @Initialized(ApplicationScoped.class) Object event) {
        String url = System.getenv("MCP_IT_JMS_URL");
        if (url == null || url.isBlank()) {
            return;
        }
        String user     = System.getenv("MCP_IT_JMS_USER");
        String password = System.getenv("MCP_IT_JMS_PASSWORD");
        ConnectionFactory factory = user == null || user.isBlank() ? new ActiveMQConnectionFactory(url) : new ActiveMQConnectionFactory(url, user, password);
        JmsNotificationBridge bridge = new JmsNotificationBridge(factory, "mcp.it.notifications", notifications::receive, 60_000L, 1_000L);
        bridge.start();
        delegate = bridge;
    }

    @Override
    public void publish(McpChange change) throws Exception {
        JmsNotificationBridge bridge = delegate;
        if (bridge != null) {
            bridge.publish(change);
        }
    }

    boolean configured() {
        return delegate != null;
    }

    boolean awaitListening(Duration timeout) throws InterruptedException {
        JmsNotificationBridge bridge = delegate;
        return bridge != null && bridge.awaitListening(timeout);
    }

    @PreDestroy
    void stop() {
        JmsNotificationBridge bridge = delegate;
        if (bridge != null) {
            bridge.close();
        }
    }
}
