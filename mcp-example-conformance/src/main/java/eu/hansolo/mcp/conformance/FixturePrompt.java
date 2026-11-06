package eu.hansolo.mcp.conformance;

import eu.hansolo.mcp.api.McpPromptHandler;
import eu.hansolo.mcp.api.PromptArgument;
import eu.hansolo.mcp.api.PromptCall;
import eu.hansolo.mcp.api.PromptResult;

import java.util.List;

/** A prompt defined by a name, arguments and a function. */
final class FixturePrompt implements McpPromptHandler {

    @FunctionalInterface
    interface Body {
        PromptResult get(PromptCall call) throws Exception;
    }

    private final String                name;
    private final String                description;
    private final List<PromptArgument>  arguments;
    private final Body                  body;


    FixturePrompt(String name, String description, List<PromptArgument> arguments, Body body) {
        this.name        = name;
        this.description = description;
        this.arguments   = arguments;
        this.body        = body;
    }


    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public List<PromptArgument> arguments() {
        return arguments;
    }

    @Override
    public PromptResult get(PromptCall call) throws Exception {
        return body.get(call);
    }
}
