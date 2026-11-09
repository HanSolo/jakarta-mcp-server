package eu.hansolo.mcp.api;

import jakarta.json.JsonObject;

/** The Tasks extension ({@code io.modelcontextprotocol/tasks}). */
public final class Tasks {

    public static final String EXTENSION_ID = "io.modelcontextprotocol/tasks";


    private Tasks() {
    }


    /** True if the client declared the Tasks extension on this request. */
    public static boolean clientSupports(JsonObject clientCapabilities) {
        return Extensions.clientSettings(clientCapabilities, EXTENSION_ID).isPresent();
    }
}
