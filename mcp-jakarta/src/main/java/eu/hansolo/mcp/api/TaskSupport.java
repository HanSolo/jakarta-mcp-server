package eu.hansolo.mcp.api;

/**
 * Whether a tool may run as a task, as announced in {@code tools/list} ({@code execution.taskSupport}).
 *
 * <p>This is a declaration for clients. A tool still decides per call whether to return {@link ToolResult#task}; the declaration
 * {@link #REQUIRED} additionally makes the server refuse a call from a client that did not declare the Tasks extension (or when
 * tasks are switched off), with the error -32021, instead of running the work inline.
 */
public enum TaskSupport {

    /** The tool never returns a task. */
    FORBIDDEN("forbidden"),

    /** The tool may return a task; a client without the extension gets the result inline. */
    OPTIONAL("optional"),

    /** The tool always runs as a task; a client without the extension is refused with -32021. */
    REQUIRED("required");

    private final String wire;

    TaskSupport(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }
}
