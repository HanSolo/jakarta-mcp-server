package eu.hansolo.mcp.example;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;
import eu.hansolo.mcp.rest.McpResource;
import eu.hansolo.mcp.rest.ProtectedResourceMetadataResource;

import java.util.Set;

/**
 * Serves the MCP endpoint at {@code /mcp} of the context root. The resource lives in the library JAR, so it is registered
 * explicitly rather than relying on archive scanning; add your own JAX-RS classes to the set if you have any.
 */
@ApplicationPath("/")
public class ExampleApplication extends Application {

    @Override
    public Set<Class<?>> getClasses() {
        return Set.of(McpResource.class, ProtectedResourceMetadataResource.class);
    }
}
