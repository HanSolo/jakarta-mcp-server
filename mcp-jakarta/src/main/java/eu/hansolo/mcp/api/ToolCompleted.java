package eu.hansolo.mcp.api;

import java.time.Duration;
import java.time.Instant;

/**
 * A CDI event, fired when a tool call ends, whatever the outcome (see {@link ToolCalled} for what it does not carry and how to
 * observe it). {@code duration} is the time the tool method took, measured with a monotonic clock.
 *
 * <p>For a tool that starts a task the outcome is {@link Outcome#TASK} and the duration is the time to start it; the work of the task
 * runs later and fires nothing.
 *
 * @param callId    the identifier from {@link ToolCalled}
 * @param tool      the tool name
 * @param subject   who called: the subject of the token, or an empty string when authentication is off
 * @param startedAt when the call started
 * @param duration  how long the tool ran
 * @param outcome   how it ended
 * @param errorType the class name of the exception for an {@link Outcome#ERROR} that came from an exception, otherwise {@code null};
 *                  never the message, which can reveal internals
 */
public record ToolCompleted(String callId, String tool, String subject, Instant startedAt, Duration duration, Outcome outcome, String errorType) {

    /** How a call ended. More values can be added in a minor release, so do not switch over them without a default. */
    public enum Outcome {
        /** The tool returned a result. */
        SUCCESS,
        /** The tool returned an {@code isError} result, or threw. */
        ERROR,
        /** The tool asked the client for more input (a multi round-trip request). */
        INPUT_REQUIRED,
        /** The tool started a task; the work continues in the background. */
        TASK,
        /** The request was cancelled while the tool ran. */
        CANCELLED
    }
}
