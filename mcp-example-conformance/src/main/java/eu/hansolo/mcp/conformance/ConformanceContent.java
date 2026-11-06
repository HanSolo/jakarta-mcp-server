package eu.hansolo.mcp.conformance;

import eu.hansolo.mcp.api.CompletionCall;
import eu.hansolo.mcp.api.CompletionResult;
import eu.hansolo.mcp.api.McpCompletionHandler;
import eu.hansolo.mcp.api.McpPromptHandler;
import eu.hansolo.mcp.api.McpResourceHandler;
import eu.hansolo.mcp.api.McpResourceTemplateHandler;
import eu.hansolo.mcp.api.PromptArgument;
import eu.hansolo.mcp.api.PromptMessage;
import eu.hansolo.mcp.api.PromptResult;
import eu.hansolo.mcp.api.ResourceContent;
import eu.hansolo.mcp.api.ResourceResult;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The prompts, resources and resource template the conformance suite looks for. */
public final class ConformanceContent {

    private ConformanceContent() {
    }


    public static Map<String, McpPromptHandler> prompts() {
        List<FixturePrompt> prompts = List.of(
                new FixturePrompt("test_simple_prompt", "A prompt without arguments.", List.of(), c -> PromptResult.of(PromptMessage.user("This is a simple prompt for testing."))),
                new FixturePrompt("test_prompt_with_arguments", "A prompt with two required arguments.", List.of(PromptArgument.required("arg1", "First argument"), PromptArgument.required("arg2", "Second argument")), c -> PromptResult.of(PromptMessage.user("Prompt with arguments: arg1='" + c.argument("arg1").orElse("") + "', arg2='" + c.argument("arg2").orElse("") + "'"))),
                new FixturePrompt("test_prompt_with_embedded_resource", "A prompt that embeds a resource.", List.of(PromptArgument.required("resourceUri", "URI of the resource to embed")), c -> PromptResult.of(PromptMessage.user(PromptMessage.embeddedResource(ResourceContent.text(c.argument("resourceUri").orElse(""), "text/plain", "Embedded resource content for testing."))), PromptMessage.user("Please process the embedded resource above."))),
                new FixturePrompt("test_prompt_with_image", "A prompt with an image.", List.of(), c -> PromptResult.of(PromptMessage.user(Content.image()), PromptMessage.user("Please analyze the image above."))),
                new FixturePrompt("test_input_required_result_prompt", "A prompt that asks the client for context.", List.of(), c -> {
                    var context = Requests.acceptedString(c.inputResponses(), "user_context", "context");
                    if (context.isPresent()) {
                        return PromptResult.of(PromptMessage.user("Prompt with context: " + context.get()));
                    }
                    return PromptResult.inputRequired(Requests.requests("user_context", Requests.elicit("What context should the prompt use?", "context", "string")), null);
                }));
        Map<String, McpPromptHandler> byName = new LinkedHashMap<>();
        prompts.forEach(p -> byName.put(p.name(), p));
        return byName;
    }

    public static Map<String, McpResourceHandler> resources() {
        List<McpResourceHandler> resources = List.of(
                FixtureResource.fixed("test://static-text", "static-text", "text/plain", c -> ResourceResult.text("test://static-text", "text/plain", "This is the content of the static text resource.")),
                FixtureResource.fixed("test://static-binary", "static-binary", "image/png", c -> ResourceResult.of(ResourceContent.blob("test://static-binary", "image/png", Base64.getDecoder().decode(Content.PNG_BASE64)))),
                FixtureResource.fixed("test://stateless-static-text", "stateless-static-text", "text/plain", c -> ResourceResult.text("test://stateless-static-text", "text/plain", "Static text content from the stateless draft path.")),
                FixtureResource.fixed("test://watched-resource", "watched-resource", "text/plain", c -> ResourceResult.text("test://watched-resource", "text/plain", "Watched resource content.")));
        Map<String, McpResourceHandler> byUri = new LinkedHashMap<>();
        resources.forEach(r -> byUri.put(r.uri(), r));
        return byUri;
    }

    public static McpResourceTemplateHandler template() {
        return FixtureResource.template("test://template/{id}/data", "template-data", "application/json", c -> {
            String id = c.variable("id").orElse("");
            String json = "{\"id\":\"" + escape(id) + "\",\"templateTest\":true,\"data\":\"Data for ID: " + escape(id) + "\"}";
            return ResourceResult.text(c.uri(), "application/json", json);
        });
    }

    /** Without a completion handler the server does not offer {@code completion/complete} at all, and the suite expects the method. */
    public static McpCompletionHandler completion() {
        return new McpCompletionHandler() {
            @Override
            public Kind kind() {
                return Kind.PROMPT;
            }

            @Override
            public String target() {
                return "test_prompt_with_arguments";
            }

            @Override
            public String argument() {
                return "arg1";
            }

            @Override
            public CompletionResult complete(CompletionCall call) {
                return CompletionResult.matching(List.of("paris", "park", "party"), call.value());
            }
        };
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
