package eu.hansolo.mcp.api;

import jakarta.json.JsonObject;

import java.util.List;

/** The outcome of resolving a prompt: the messages, or a request for more input from the client (multi round-trip). */
public sealed interface PromptResult permits PromptResult.Complete, PromptResult.InputRequired {

    /** @param description optional description of the resolved prompt, or null */
    record Complete(String description, List<PromptMessage> messages) implements PromptResult {

        public Complete {
            messages = List.copyOf(messages);
        }
    }


    record InputRequired(JsonObject inputRequests, String requestState) implements PromptResult {

        public InputRequired {
            if (inputRequests == null && requestState == null) {
                throw new IllegalArgumentException("inputRequests or requestState is required");
            }
        }
    }

    static Complete of(PromptMessage... messages) {
        return new Complete(null, List.of(messages));
    }

    static Complete of(String description, List<PromptMessage> messages) {
        return new Complete(description, messages);
    }

    static InputRequired inputRequired(JsonObject inputRequests, String requestState) {
        return new InputRequired(inputRequests, requestState);
    }

    /** Asks the client for input ({@link Elicitation}) and has it retry the request with the answers. */
    static InputRequired ask(InputRequests requests, String requestState) {
        return new InputRequired(requests.toJson(), requestState);
    }
}
