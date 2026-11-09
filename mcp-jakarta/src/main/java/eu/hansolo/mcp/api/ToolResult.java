package eu.hansolo.mcp.api;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;

/** The outcome of a tool call: either a final result or a request for more input from the client. */
public sealed interface ToolResult permits ToolResult.Complete, ToolResult.InputRequired {

    /**
     * A final result.
     *
     * @param content           unstructured content blocks, never null
     * @param structuredContent optional structured result (any JSON value), or null
     * @param isError           true if the tool ran but failed; the model gets to see the message and can recover
     * @param meta              optional {@code _meta} of the result (not shown to the model by most clients; MCP Apps and
     *                          extensions use it), or null
     */
    record Complete(JsonArray content, JsonValue structuredContent, boolean isError, JsonObject meta) implements ToolResult {

        public Complete {
            Meta.validate(meta, "tool result _meta");
        }

        public Complete(JsonArray content, JsonValue structuredContent, boolean isError) {
            this(content, structuredContent, isError, null);
        }

        /** The same result with a {@code _meta} object. The server's own {@code serverInfo} key is added on top. */
        public Complete withMeta(JsonObject meta) {
            return new Complete(content, structuredContent, isError, meta);
        }
    }

    /**
     * Multi Round-Trip Request: the tool needs something from the client (for example an elicitation) before it can finish.
     * The client retries the original call with {@code inputResponses} attached. At least one field must be non-null.
     */
    record InputRequired(JsonObject inputRequests, String requestState) implements ToolResult {

        public InputRequired {
            if (inputRequests == null && requestState == null) {
                throw new IllegalArgumentException("inputRequests or requestState is required");
            }
        }
    }

    static ToolResult text(String text) {
        return new Complete(Json.createArrayBuilder().add(textBlock(text)).build(), null, false);
    }

    /** A tool failure the model should see. Do not put secrets or stack traces in the message. */
    static ToolResult error(String message) {
        return new Complete(Json.createArrayBuilder().add(textBlock(message)).build(), null, true);
    }

    /** Structured result; the JSON text is also returned as a text block for clients that ignore structuredContent. */
    static ToolResult structured(JsonObject data) {
        return new Complete(Json.createArrayBuilder().add(textBlock(data.toString())).build(), data, false);
    }

    /** A result made of content blocks built with {@link Content}. */
    static Complete of(JsonObject... blocks) {
        return new Complete(Content.array(blocks), null, false);
    }

    /** Content blocks plus a structured result. */
    static Complete structured(JsonObject structuredContent, JsonObject... blocks) {
        return new Complete(Content.array(blocks), structuredContent, false);
    }

    static ToolResult inputRequired(JsonObject inputRequests, String requestState) {
        return new InputRequired(inputRequests, requestState);
    }

    /** Asks the client for input ({@link Elicitation}) and has it retry the call with the answers. {@code requestState} may be null; if it is not, sign it ({@link eu.hansolo.mcp.server.McpRequestStates}). */
    static ToolResult ask(InputRequests requests, String requestState) {
        return new InputRequired(requests.toJson(), requestState);
    }

    private static JsonObject textBlock(String text) {
        return Json.createObjectBuilder().add("type", "text").add("text", text).build();
    }
}
