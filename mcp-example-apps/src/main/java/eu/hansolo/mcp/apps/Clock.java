package eu.hansolo.mcp.apps;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import eu.hansolo.mcp.api.Content;
import eu.hansolo.mcp.api.ToolResult;

import java.time.Instant;

/** The result both clock tools return: a text line for everyone and the time as structured content for the page. */
final class Clock {

    private Clock() {
    }


    static ToolResult result(String prefix) {
        String     now  = Instant.now().toString();
        JsonObject data = Json.createObjectBuilder().add("now", now).build();
        return new ToolResult.Complete(Content.array(Content.text(prefix + now)), data, false);
    }
}
