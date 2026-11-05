package eu.hansolo.mcp.tickets;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;
import eu.hansolo.mcp.rest.McpResource;
import eu.hansolo.mcp.rest.ProtectedResourceMetadataResource;

import java.util.Set;

/** Serves the MCP endpoint at {@code /mcp} and the OAuth metadata under {@code /.well-known}. */
@ApplicationPath("/")
public class TicketsApplication extends Application {

    @Override
    public Set<Class<?>> getClasses() {
        return Set.of(McpResource.class, ProtectedResourceMetadataResource.class);
    }
}
