package eu.hansolo.mcp.itfixture;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import eu.hansolo.mcp.api.McpExtension;

/** An extension announced in {@code server/discover}, so the integration test can see the negotiation work on a real server. */
@ApplicationScoped
public class FixtureExtension implements McpExtension {

    public static final String ID = "com.example/it-fixture";


    @Override
    public String id() {
        return ID;
    }

    @Override
    public JsonObject settings() {
        return Json.createObjectBuilder().add("level", 1).build();
    }
}
