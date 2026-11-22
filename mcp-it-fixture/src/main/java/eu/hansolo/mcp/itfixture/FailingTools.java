package eu.hansolo.mcp.itfixture;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.NotFoundException;
import eu.hansolo.mcp.api.McpParam;
import eu.hansolo.mcp.api.McpTool;

/** Tools that throw: what the model is told depends on the exception and on {@link FixtureFailureMapper}. */
@ApplicationScoped
public class FailingTools {

    @McpTool(description = "Fails with a JAX-RS NotFoundException")
    public String fail_not_found(@McpParam(description = "Name of the duck") String name) {
        throw new NotFoundException("No duck named " + name);
    }

    @McpTool(description = "Fails with an exception that the fixture mapper explains")
    public String fail_business(@McpParam(description = "Name of the duck") String name) {
        throw new IllegalArgumentException("Duck " + name + " is not bookable today");
    }

    @McpTool(description = "Fails with an exception that nobody explains")
    public String fail_secret(@McpParam(description = "Name of the duck") String name) {
        throw new IllegalStateException("jdbc:postgresql://db.internal/ducks password=hunter2");
    }
}
