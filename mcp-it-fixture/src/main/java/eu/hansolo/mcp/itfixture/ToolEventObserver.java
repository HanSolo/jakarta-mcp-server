package eu.hansolo.mcp.itfixture;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import eu.hansolo.mcp.api.ToolCalled;
import eu.hansolo.mcp.api.ToolCompleted;

/** Writes the CDI events of tool calls into the call log: what an audit trail or a metrics bridge would observe. */
@ApplicationScoped
public class ToolEventObserver {

    @Inject CallLog log;


    void called(@Observes ToolCalled event) {
        if (event.tool().startsWith("bean_") || event.tool().startsWith("fail_")) {
            log.record("event called " + event.tool());
        }
    }

    void completed(@Observes ToolCompleted event) {
        if (event.tool().startsWith("bean_") || event.tool().startsWith("fail_")) {
            log.record("event completed " + event.tool() + " " + event.outcome() + (event.errorType() == null ? "" : " " + event.errorType()));
        }
    }

    void completedAsync(@ObservesAsync ToolCompleted event) {
        if (event.tool().startsWith("bean_") || event.tool().startsWith("fail_")) {
            log.record("async completed " + event.tool());
        }
    }
}
