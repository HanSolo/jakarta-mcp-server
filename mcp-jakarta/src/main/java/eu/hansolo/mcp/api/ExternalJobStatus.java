package eu.hansolo.mcp.api;

/**
 * What {@link ExternalJob#poll} found out about a job in another system.
 *
 * <p><b>Sealed.</b> Applications create these values with the factory methods and return them; they do not switch over them. A minor release
 * can add a variant, which would break an exhaustive {@code switch} in application code.
 */
public sealed interface ExternalJobStatus permits ExternalJobStatus.Running, ExternalJobStatus.Completed, ExternalJobStatus.Failed {

    /**
     * The job is still going.
     *
     * @param message      shown as the status message of the task, or null to leave it as it is
     * @param retryAfterMs when to ask again, or null for {@code mcp.tasks.external-poll-ms}
     */
    record Running(String message, Long retryAfterMs) implements ExternalJobStatus {

        public Running {
            if (retryAfterMs != null && retryAfterMs <= 0) {
                throw new IllegalArgumentException("retryAfterMs must be positive");
            }
        }
    }

    /** The job is done; the task ends as {@code completed} with this result. */
    record Completed(ToolResult.Complete result) implements ExternalJobStatus {

        public Completed {
            if (result == null) {
                throw new IllegalArgumentException("A completed job needs a result");
            }
        }
    }

    /** The job failed or is gone; the task ends as {@code failed} with this message. */
    record Failed(String message) implements ExternalJobStatus {

        public Failed {
            message = message == null || message.isBlank() ? "The job failed" : message;
        }
    }


    static ExternalJobStatus running() {
        return new Running(null, null);
    }

    static ExternalJobStatus running(String message) {
        return new Running(message, null);
    }

    static ExternalJobStatus running(String message, long retryAfterMs) {
        return new Running(message, retryAfterMs);
    }

    static ExternalJobStatus completed(ToolResult.Complete result) {
        return new Completed(result);
    }

    static ExternalJobStatus failed(String message) {
        return new Failed(message);
    }
}
