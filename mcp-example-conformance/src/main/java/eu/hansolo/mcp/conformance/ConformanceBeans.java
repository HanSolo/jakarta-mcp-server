package eu.hansolo.mcp.conformance;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import eu.hansolo.mcp.api.McpCompletionHandler;
import eu.hansolo.mcp.api.McpPromptHandler;
import eu.hansolo.mcp.api.McpResourceHandler;
import eu.hansolo.mcp.api.McpResourceTemplateHandler;
import eu.hansolo.mcp.api.McpToolHandler;
import eu.hansolo.mcp.server.McpNotifications;
import eu.hansolo.mcp.server.McpRequestStates;

import java.util.Map;

/**
 * Hands the fixtures to CDI one bean each, which is how the library finds handlers. The fixtures themselves are plain objects built
 * by {@link ConformanceTools} and {@link ConformanceContent}, so tests can use them without a container.
 */
@ApplicationScoped
public class ConformanceBeans {

    private final Map<String, McpToolHandler>     tools;
    private final Map<String, McpPromptHandler>   prompts;
    private final Map<String, McpResourceHandler> resources;


    protected ConformanceBeans() {
        this.tools     = null;
        this.prompts   = null;
        this.resources = null;
    }

    @Inject
    public ConformanceBeans(McpNotifications notifications, McpRequestStates states) {
        this.tools     = new ConformanceTools(notifications, states).all();
        this.prompts   = ConformanceContent.prompts();
        this.resources = ConformanceContent.resources();
    }

    @Produces
    McpToolHandler conformancePingTool() {
        return tools.get("a_conformance_ping");
    }

    @Produces
    McpToolHandler greetTool() {
        return tools.get("greet");
    }

    @Produces
    McpToolHandler slowComputeTool() {
        return tools.get("slow_compute");
    }

    @Produces
    McpToolHandler failingJobTool() {
        return tools.get("failing_job");
    }

    @Produces
    McpToolHandler protocolErrorJobTool() {
        return tools.get("protocol_error_job");
    }

    @Produces
    McpToolHandler confirmDeleteTool() {
        return tools.get("confirm_delete");
    }

    @Produces
    McpToolHandler multiInputTool() {
        return tools.get("multi_input");
    }

    @Produces
    McpToolHandler testToolWithTaskTool() {
        return tools.get("test_tool_with_task");
    }

    @Produces
    McpToolHandler testSimpleTextTool() {
        return tools.get("test_simple_text");
    }

    @Produces
    McpToolHandler testImageContentTool() {
        return tools.get("test_image_content");
    }

    @Produces
    McpToolHandler testAudioContentTool() {
        return tools.get("test_audio_content");
    }

    @Produces
    McpToolHandler testEmbeddedResourceTool() {
        return tools.get("test_embedded_resource");
    }

    @Produces
    McpToolHandler testMultipleContentTypesTool() {
        return tools.get("test_multiple_content_types");
    }

    @Produces
    McpToolHandler testErrorHandlingTool() {
        return tools.get("test_error_handling");
    }

    @Produces
    McpToolHandler testToolWithProgressTool() {
        return tools.get("test_tool_with_progress");
    }

    @Produces
    McpToolHandler testMissingCapabilityTool() {
        return tools.get("test_missing_capability");
    }

    @Produces
    McpToolHandler testStreamingElicitationTool() {
        return tools.get("test_streaming_elicitation");
    }

    @Produces
    McpToolHandler testLoggingToolTool() {
        return tools.get("test_logging_tool");
    }

    @Produces
    McpToolHandler testTriggerToolChangeTool() {
        return tools.get("test_trigger_tool_change");
    }

    @Produces
    McpToolHandler testTriggerPromptChangeTool() {
        return tools.get("test_trigger_prompt_change");
    }

    @Produces
    McpToolHandler testCustomHeaderTool() {
        return tools.get("test_custom_header");
    }

    @Produces
    McpToolHandler jsonSchema202012ToolTool() {
        return tools.get("json_schema_2020_12_tool");
    }

    @Produces
    McpToolHandler testInputRequiredResultElicitationTool() {
        return tools.get("test_input_required_result_elicitation");
    }

    @Produces
    McpToolHandler testInputRequiredResultSamplingTool() {
        return tools.get("test_input_required_result_sampling");
    }

    @Produces
    McpToolHandler testInputRequiredResultListRootsTool() {
        return tools.get("test_input_required_result_list_roots");
    }

    @Produces
    McpToolHandler testInputRequiredResultRequestStateTool() {
        return tools.get("test_input_required_result_request_state");
    }

    @Produces
    McpToolHandler testInputRequiredResultMultipleInputsTool() {
        return tools.get("test_input_required_result_multiple_inputs");
    }

    @Produces
    McpToolHandler testInputRequiredResultMultiRoundTool() {
        return tools.get("test_input_required_result_multi_round");
    }

    @Produces
    McpToolHandler testInputRequiredResultTamperedStateTool() {
        return tools.get("test_input_required_result_tampered_state");
    }

    @Produces
    McpToolHandler testInputRequiredResultCapabilitiesTool() {
        return tools.get("test_input_required_result_capabilities");
    }

    @Produces
    McpPromptHandler testSimplePromptPrompt() {
        return prompts.get("test_simple_prompt");
    }

    @Produces
    McpPromptHandler testPromptWithArgumentsPrompt() {
        return prompts.get("test_prompt_with_arguments");
    }

    @Produces
    McpPromptHandler testPromptWithEmbeddedResourcePrompt() {
        return prompts.get("test_prompt_with_embedded_resource");
    }

    @Produces
    McpPromptHandler testPromptWithImagePrompt() {
        return prompts.get("test_prompt_with_image");
    }

    @Produces
    McpPromptHandler testInputRequiredResultPromptPrompt() {
        return prompts.get("test_input_required_result_prompt");
    }

    @Produces
    McpResourceHandler staticTextResource() {
        return resources.get("test://static-text");
    }

    @Produces
    McpResourceHandler staticBinaryResource() {
        return resources.get("test://static-binary");
    }

    @Produces
    McpResourceHandler statelessStaticTextResource() {
        return resources.get("test://stateless-static-text");
    }

    @Produces
    McpResourceHandler watchedResourceResource() {
        return resources.get("test://watched-resource");
    }

    @Produces
    McpResourceTemplateHandler templateDataResource() {
        return ConformanceContent.template();
    }

    @Produces
    McpCompletionHandler promptArgumentCompletion() {
        return ConformanceContent.completion();
    }
}
