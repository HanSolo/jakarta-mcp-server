package eu.hansolo.mcp.itfixture;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import eu.hansolo.mcp.api.McpParam;
import eu.hansolo.mcp.api.McpTool;

/** An {@code @McpTool} method behind a CDI interceptor: the interceptor sees every call and can stop it before the method runs. */
@ApplicationScoped
public class AuditedTool {

    @Inject CallLog log;


    @McpTool(description = "Echoes the message; every call goes through an interceptor")
    @Audited
    public String audited_echo(@McpParam(description = "Text to echo") String message) {
        log.record("ran audited_echo " + message);
        return "echo: " + message;
    }
}
