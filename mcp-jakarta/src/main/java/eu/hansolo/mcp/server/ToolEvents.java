package eu.hansolo.mcp.server;

import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import eu.hansolo.mcp.api.ToolCalled;
import eu.hansolo.mcp.api.ToolCompleted;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Fires {@link ToolCalled} and {@link ToolCompleted} around a tool call, synchronously for {@code @Observes} and asynchronously for
 * {@code @ObservesAsync}. An observer that throws is logged and ignored.
 *
 * <p><b>Internal.</b> Not part of the stable API: this class is public only because other packages of this library use it, and it can
 * change in any release. Applications observe the events instead.
 */
@Singleton
public class ToolEvents {

    private static final Logger LOG = Logger.getLogger(ToolEvents.class.getName());

    private final Event<ToolCalled>    called;
    private final Event<ToolCompleted> completed;


    @Inject
    public ToolEvents(Event<ToolCalled> called, Event<ToolCompleted> completed) {
        this.called    = called;
        this.completed = completed;
    }


    /** Fires nothing (for code that is built without CDI). */
    public static ToolEvents none() {
        return new ToolEvents(null, null);
    }


    /** Fires {@link ToolCalled} and returns the handle with which the call is ended. */
    Run begin(String tool, String subject) {
        Run run = new Run(UUID.randomUUID().toString(), tool, subject == null ? "" : subject, Instant.now(), System.nanoTime());
        if (called != null) {
            ToolCalled event = new ToolCalled(run.callId, tool, run.subject, run.startedAt);
            try {
                called.fire(event);
                called.fireAsync(event);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "An observer of ToolCalled failed for tool '" + tool + "'", e);
            }
        }
        return run;
    }


    /** One call that has begun. */
    final class Run {

        private final String  callId;
        private final String  tool;
        private final String  subject;
        private final Instant startedAt;
        private final long    startNanos;
        private boolean       finished;


        private Run(String callId, String tool, String subject, Instant startedAt, long startNanos) {
            this.callId     = callId;
            this.tool       = tool;
            this.subject    = subject;
            this.startedAt  = startedAt;
            this.startNanos = startNanos;
        }


        /** Fires {@link ToolCompleted}, once; a second call does nothing. */
        void end(ToolCompleted.Outcome outcome, String errorType) {
            if (finished) {
                return;
            }
            finished = true;
            if (completed == null) {
                return;
            }
            ToolCompleted event = new ToolCompleted(callId, tool, subject, startedAt, Duration.ofNanos(System.nanoTime() - startNanos), outcome, errorType);
            try {
                completed.fire(event);
                completed.fireAsync(event);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "An observer of ToolCompleted failed for tool '" + tool + "'", e);
            }
        }
    }
}
