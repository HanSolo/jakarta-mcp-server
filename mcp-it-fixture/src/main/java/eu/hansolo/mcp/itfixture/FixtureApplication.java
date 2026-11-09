package eu.hansolo.mcp.itfixture;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;
import eu.hansolo.mcp.rest.McpResource;
import eu.hansolo.mcp.rest.ProtectedResourceMetadataResource;

import java.util.Set;

/** The MCP endpoint at {@code /mcp} plus the test-only {@link AdminResource} at {@code /admin}. */
@ApplicationPath("/")
public class FixtureApplication extends Application {

    @Override
    public Set<Class<?>> getClasses() {
        return Set.of(McpResource.class, ProtectedResourceMetadataResource.class, AdminResource.class);
    }
}
