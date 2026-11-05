package eu.hansolo.mcp.example;

import jakarta.enterprise.context.ApplicationScoped;
import eu.hansolo.mcp.api.McpResource;
import eu.hansolo.mcp.protocol.McpProtocolException;

import java.util.Map;

/** Resources by annotation: one fixed resource and one template. */
@ApplicationScoped
public class ExampleResources {

    public record ServerInfo(String name, String purpose, String runtime) {
    }


    @McpResource(uri = "config://server/info", name = "server-info", description = "What this example server is and what it runs on.")
    public ServerInfo info() {
        return new ServerInfo("jakarta-mcp-example", "Example MCP server using only Jakarta EE", "Java " + Runtime.version().feature());
    }

    @McpResource(uriTemplate = "greeting://{name}", name = "greeting", description = "A personal greeting for the name in the URI.", mimeType = "text/plain")
    public String greeting(String name) {
        if (name.isBlank() || name.length() > 100) {
            throw McpProtocolException.invalidArguments("The name must be 1 to 100 characters");
        }
        return "Hello, " + name + "!";
    }
}
