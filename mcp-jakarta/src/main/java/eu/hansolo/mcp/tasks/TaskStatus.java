package eu.hansolo.mcp.tasks;

/** The states of a task. The last three are terminal: once reached, a task does not change any more. */
public enum TaskStatus {

    WORKING("working"),
    INPUT_REQUIRED("input_required"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled");


    private final String wire;


    TaskStatus(String wire) {
        this.wire = wire;
    }


    /** The name used in the protocol. */
    public String wire() {
        return wire;
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    public static TaskStatus fromWire(String wire) {
        for (TaskStatus status : values()) {
            if (status.wire.equals(wire)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown task status '" + wire + "'");
    }
}
