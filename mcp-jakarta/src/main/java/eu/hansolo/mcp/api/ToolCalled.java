package eu.hansolo.mcp.api;

import java.time.Instant;

/**
 * A CDI event, fired just before a tool runs: the request is authenticated, the scopes are checked and the arguments have been
 * validated against the schema. Observe it with {@code @Observes ToolCalled} (the call waits for the observer) or
 * {@code @ObservesAsync ToolCalled}; the library fires both kinds. It is the start of an audit record or a metric; the matching end
 * is {@link ToolCompleted} with the same {@code callId}.
 *
 * <p>The event carries no arguments and no result, because both can hold personal data. A tool that wants them in its audit trail
 * writes them itself, for example from a CDI interceptor. A failing observer is logged and does not stop the call. Requests that are
 * refused before the tool runs (unknown tool, missing scope, invalid arguments) fire nothing.
 *
 * @param callId  an identifier of this call, the same in {@link ToolCompleted}
 * @param tool    the tool name
 * @param subject who is calling: the subject of the token, or an empty string when authentication is off
 * @param at      when the call started
 */
public record ToolCalled(String callId, String tool, String subject, Instant at) {
}
