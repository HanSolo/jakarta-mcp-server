package eu.hansolo.mcp.apps;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;
import eu.hansolo.mcp.rest.McpResource;
import eu.hansolo.mcp.rest.ProtectedResourceMetadataResource;

import java.util.Set;

/** Serves the MCP endpoint at {@code /mcp} of the context root, like the other example WARs. */
@ApplicationPath("/")
public class AppsApplication extends Application {

    @Override
    public Set<Class<?>> getClasses() {
        return Set.of(McpResource.class, ProtectedResourceMetadataResource.class);
    }
}
