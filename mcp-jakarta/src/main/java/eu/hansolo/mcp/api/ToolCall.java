package eu.hansolo.mcp.api;

import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import java.util.Optional;

/**
 * Everything a tool receives for one call. Nothing here comes from an earlier request.
 *
 * @param name               the tool name
 * @param arguments          validated arguments, never null
 * @param clientCapabilities capabilities the client declared on this request, never null
 * @param clientInfo         self-reported client identity, or null (display and logging only, never a security decision)
 * @param inputResponses     answers to a previous {@code input_required} result (Multi Round-Trip Requests), or null
 * @param requestState       opaque state the tool returned earlier, or null; it is client-controlled, so authenticate it before trusting it
 * @param caller             who is calling, from this request's access token; {@link Caller#ANONYMOUS} when authentication is off
 * @param progress           where progress notifications go; {@link ProgressReporter#NONE} unless the client asked for a streamed response
 * @param trace              the OpenTelemetry trace context of the request, never null ({@link TraceContext#EMPTY} if the client sent none)
 */
public record ToolCall(String name, JsonObject arguments, JsonObject clientCapabilities, JsonObject clientInfo, JsonObject inputResponses, String requestState, Caller caller, ProgressReporter progress, TraceContext trace) {

    public ToolCall {
        progress = progress == null ? ProgressReporter.NONE : progress;
        trace    = trace == null ? TraceContext.EMPTY : trace;
    }

    /** A call without trace context. */
    public ToolCall(String name, JsonObject arguments, JsonObject clientCapabilities, JsonObject clientInfo, JsonObject inputResponses, String requestState, Caller caller, ProgressReporter progress) {
        this(name, arguments, clientCapabilities, clientInfo, inputResponses, requestState, caller, progress, TraceContext.EMPTY);
    }

    /** A call without progress reporting. */
    public ToolCall(String name, JsonObject arguments, JsonObject clientCapabilities, JsonObject clientInfo, JsonObject inputResponses, String requestState, Caller caller) {
        this(name, arguments, clientCapabilities, clientInfo, inputResponses, requestState, caller, ProgressReporter.NONE);
    }

    /**
     * Tells the client how far the call is. A no-op unless the client asked for progress, so it is always safe to call. It is
     * also how a tool notices that the client has gone away: it throws {@link RequestCancelledException}, which should propagate.
     *
     * @param progress increasing with every call
     * @param total    the expected final value, or null
     * @param message  short status text, or null
     */
    public void progress(double progress, Double total, String message) {
        this.progress.report(progress, total, message);
    }

    /**
     * True once the server has noticed that the client closed a streamed call. A tool that does not report progress can poll this in
     * its loops and stop; one that reports progress is told by {@link RequestCancelledException} on the next report. Always false for a
     * call that is not streamed.
     */
    public boolean isCancelled() {
        return progress.isCancelled();
    }

    /** True if the client receives progress notifications, so a tool can skip work that only exists to report. */
    public boolean wantsProgress() {
        return progress.isActive();
    }

    /** True if the client can show this kind of elicitation ({@link Elicitation#supports}). Ask only for modes it declared. */
    public boolean clientSupportsElicitation(Elicitation.Mode mode) {
        return Elicitation.supports(clientCapabilities, mode);
    }

    public boolean clientSupports(String capability) {
        return clientCapabilities.containsKey(capability);
    }

    /** The client's settings for an extension it declared on this request, or empty (then fall back to core behaviour). */
    public Optional<JsonObject> clientExtension(String id) {
        return Extensions.clientSettings(clientCapabilities, id);
    }

    /** True if the client can show MCP Apps pages ({@link Apps#clientSupports}). If not, return a plain text result. */
    public boolean clientSupportsApps() {
        return Apps.clientSupports(clientCapabilities);
    }

    /** True if the client declared the Tasks extension, so {@link ToolResult#task} gives it a task id instead of the finished result. */
    public boolean clientSupportsTasks() {
        return Tasks.clientSupports(clientCapabilities);
    }

    /** Refuses the request (error -32021) unless the client declared this extension. For extensions that are mandatory for this operation. */
    public void requireClientExtension(String id) {
        Extensions.require(clientCapabilities, id);
    }

    /** The string argument with this name, if present and a string. */
    public Optional<String> stringArgument(String key) {
        return arguments.get(key) instanceof JsonString s ? Optional.of(s.getString()) : Optional.empty();
    }

    /** The client's answer to the input request with this key, if the call is a retry that carries one. */
    public Optional<JsonObject> inputResponse(String key) {
        if (inputResponses == null) {
            return Optional.empty();
        }
        JsonValue value = inputResponses.get(key);
        return value instanceof JsonObject o ? Optional.of(o) : Optional.empty();
    }
}
