package eu.hansolo.mcp.api;

/** One argument of a prompt. Prompt arguments are always strings. */
public record PromptArgument(String name, String title, String description, boolean required) {

    /** A required argument. */
    public static PromptArgument required(String name, String description) {
        return new PromptArgument(name, null, description, true);
    }

    /** An optional argument. */
    public static PromptArgument optional(String name, String description) {
        return new PromptArgument(name, null, description, false);
    }
}
