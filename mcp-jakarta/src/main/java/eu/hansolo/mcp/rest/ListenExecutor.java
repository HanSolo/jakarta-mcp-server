package eu.hansolo.mcp.rest;

import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import eu.hansolo.mcp.server.McpConfig;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Where {@code subscriptions/listen} streams run when {@code mcp.listen.async} is on. By default that is a JDK virtual thread per
 * stream, so a stream that does nothing for minutes costs no platform thread and the container's request threads stay free.
 *
 * <p>A stream needs no container context: it is already authenticated and only waits for notifications and writes to an
 * output stream that was obtained before the hand-over. An unmanaged virtual thread is therefore enough. To run the streams on an
 * executor the container manages instead, define one in the application and name it:
 *
 * <pre>{@code
 * @ManagedExecutorDefinition(name = "java:app/concurrent/McpListen", virtual = true)
 * ...
 * mcp.listen.executor=java:app/concurrent/McpListen
 * }</pre>
 *
 * If that name cannot be looked up, the virtual threads are used and a warning is logged.
 *
 * <p><b>Internal.</b> Not part of the stable API: this class is public only because other packages of this library use it,
 * and it can change in any release. Applications should not call it.
 */
@Singleton
public class ListenExecutor {

    private static final Logger LOG = Logger.getLogger(ListenExecutor.class.getName());

    private final String name;

    private ExecutorService owned;
    private Executor        resolved;


    @Inject
    public ListenExecutor(McpConfig config) {
        this.name = config.listenExecutor();
    }


    /** The executor to hand a stream to. */
    public synchronized Executor executor() {
        if (resolved == null) {
            resolved = lookup();
            if (resolved == null) {
                owned    = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("mcp-listen-", 0).factory());
                resolved = owned;
            }
        }
        return resolved;
    }

    private Executor lookup() {
        if (name == null) {
            return null;
        }
        try {
            Object found = new InitialContext().lookup(name);
            if (found instanceof Executor executor) {
                return executor;
            }
            LOG.warning("mcp.listen.executor '" + name + "' is not an Executor (" + found.getClass().getName() + "); using virtual threads");
        } catch (NamingException | RuntimeException e) {
            LOG.log(Level.WARNING, "mcp.listen.executor '" + name + "' could not be looked up; using virtual threads", e);
        }
        return null;
    }

    /** Stops the threads this class started itself. A managed executor belongs to the container and is left alone. */
    @PreDestroy
    public synchronized void close() {
        if (owned != null) {
            owned.shutdownNow();
        }
    }
}
