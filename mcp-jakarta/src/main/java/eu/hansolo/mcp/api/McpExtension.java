package eu.hansolo.mcp.api;

import jakarta.json.Json;
import jakarta.json.JsonObject;

/**
 * Announces an MCP extension in {@code server/discover} ({@code capabilities.extensions}). Implement it in a CDI bean:
 *
 * <pre>{@code
 * @ApplicationScoped
 * public class UiExtension implements McpExtension {
 *     public String id() { return "io.modelcontextprotocol/ui"; }
 * }
 * }</pre>
 *
 * <p>Extensions are off unless you declare them. Declaring one is a promise that this server implements it: the library only
 * advertises, it does not add behaviour. The client's side of the negotiation is on the call objects
 * ({@link ToolCall#clientExtension(String)}): a server falls back to core behaviour when the client does not declare an extension,
 * or refuses with {@link ToolCall#requireClientExtension(String)} when the extension is mandatory for that request.
 */
public interface McpExtension {

    /** {@code vendor-prefix/extension-name}, for example {@code io.modelcontextprotocol/ui} or {@code com.example/reports}. */
    String id();

    /** The extension's settings, as its specification defines them. The default is an empty object (no settings). */
    default JsonObject settings() {
        return Json.createObjectBuilder().build();
    }
}
