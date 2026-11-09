package eu.hansolo.mcp.api;

/**
 * Sends progress notifications to the client while a tool runs. A tool normally reaches it through {@link ToolCall#progress}.
 *
 * <p>Progress is only delivered when the client asked for it (it sent a {@code progressToken}) and accepts a streamed response
 * ({@code Accept: text/event-stream}); otherwise {@link #NONE} is used and every call is a no-op, so a tool can report
 * progress unconditionally.
 */
public interface ProgressReporter {

    /** Does nothing. Used when the client did not ask for progress. */
    ProgressReporter NONE = new ProgressReporter() {
        @Override
        public void report(double progress, Double total, String message) {
        }

        @Override
        public boolean isActive() {
            return false;
        }
    };

    /**
     * Sends one {@code notifications/progress}.
     *
     * @param progress how far the work is; must be larger than in the previous report of this call
     * @param total    the expected final value, or null if unknown
     * @param message  a short human-readable status, or null
     * @throws RequestCancelledException if the client closed the connection; the tool should stop and let the exception propagate
     * @throws IllegalArgumentException  if progress did not increase
     */
    void report(double progress, Double total, String message);

    /** True if reports reach a client. */
    boolean isActive();

    /** True once the server has noticed that the client closed the stream. The next {@link #report} throws {@link RequestCancelledException}. */
    default boolean isCancelled() {
        return false;
    }
}
