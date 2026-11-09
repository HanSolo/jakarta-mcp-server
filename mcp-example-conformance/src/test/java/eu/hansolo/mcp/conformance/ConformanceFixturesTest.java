package eu.hansolo.mcp.conformance;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import eu.hansolo.mcp.api.Caller;
import eu.hansolo.mcp.api.McpToolHandler;
import eu.hansolo.mcp.api.PromptCall;
import eu.hansolo.mcp.api.PromptResult;
import eu.hansolo.mcp.api.ResourceCall;
import eu.hansolo.mcp.api.ResourceResult;
import eu.hansolo.mcp.api.ToolCall;
import eu.hansolo.mcp.api.ToolResult;
import eu.hansolo.mcp.protocol.McpProtocolException;
import eu.hansolo.mcp.server.McpConfig;
import eu.hansolo.mcp.server.McpNotifications;
import eu.hansolo.mcp.server.McpRegistry;
import eu.hansolo.mcp.server.McpRequestHandler;
import eu.hansolo.mcp.server.McpRequestStates;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConformanceFixturesTest {

    private final McpConfig                 config = new McpConfig();
    private final McpNotifications          notifications = new McpNotifications(config);
    private final McpRequestStates          states = new McpRequestStates(config);
    private final Map<String, McpToolHandler> tools = new ConformanceTools(notifications, states).all();


    @Test
    void imageAndAudioAreWellFormed() {
        byte[] png = Base64.getDecoder().decode(Content.PNG_BASE64);
        assertEquals(0x89, png[0] & 0xFF);
        assertEquals("PNG", new String(png, 1, 3, StandardCharsets.US_ASCII));
        byte[] wav = Content.wav();
        assertEquals("RIFF", new String(wav, 0, 4, StandardCharsets.US_ASCII));
        assertEquals("WAVE", new String(wav, 8, 4, StandardCharsets.US_ASCII));
        assertEquals(wav.length - 8, java.nio.ByteBuffer.wrap(wav, 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt());
    }

    @Test
    void registryAcceptsEveryFixtureAndTheFirstToolTakesNoArguments() {
        McpRegistry registry = McpRegistry.of(tools.values(), ConformanceContent.resources().values(), List.of(ConformanceContent.template()), ConformanceContent.prompts().values(), List.of(ConformanceContent.completion()));
        McpRequestHandler handler = new McpRequestHandler(registry, config);
        JsonObject listed = send(handler, "tools/list", Json.createObjectBuilder().build(), null);
        JsonObject first = listed.getJsonArray("tools").getJsonObject(0);
        assertEquals(tools.size(), listed.getJsonArray("tools").size());
        assertEquals("a_conformance_ping", first.getString("name"));
        JsonObject call = Json.createObjectBuilder().add("name", first.getString("name")).add("arguments", Json.createObjectBuilder()).build();
        assertEquals("complete", send(handler, "tools/call", call, first.getString("name")).getString("resultType"));
    }

    @Test
    void everyBeanProducerMatchesAFixture() {
        long producers = Arrays.stream(ConformanceBeans.class.getDeclaredMethods()).filter(m -> m.isAnnotationPresent(jakarta.enterprise.inject.Produces.class)).count();
        assertEquals(tools.size() + ConformanceContent.prompts().size() + ConformanceContent.resources().size() + 2, producers);
        for (Method m : ConformanceBeans.class.getDeclaredMethods()) {
            if (m.isAnnotationPresent(jakarta.enterprise.inject.Produces.class)) {
                assertTrue(m.getReturnType().getSimpleName().startsWith("Mcp"), m.getName());
            }
        }
    }

    @Test
    void missingCapabilityIsAnHttp400() throws Exception {
        McpProtocolException e = assertThrows(McpProtocolException.class, () -> call("test_missing_capability", null, null, caps()));
        assertEquals(400, e.httpStatus());
        assertEquals(-32021, e.code());
        assertText("Success", call("test_missing_capability", null, null, caps("sampling")));
    }

    @Test
    void elicitationRoundTrip() throws Exception {
        ToolResult first = call("test_input_required_result_elicitation", null, null, caps("elicitation"));
        JsonObject requests = ((ToolResult.InputRequired) first).inputRequests();
        assertEquals("elicitation/create", requests.getJsonObject("user_name").getString("method"));
        JsonObject answers = answers("user_name", "{\"action\":\"accept\",\"content\":{\"name\":\"Ada\"}}");
        assertText("Hello, Ada!", call("test_input_required_result_elicitation", answers, null, caps("elicitation")));
    }

    @Test
    void samplingAndRootsRoundTrips() throws Exception {
        assertText("Sampling result: Paris", call("test_input_required_result_sampling", answers("sample_request", "{\"role\":\"assistant\",\"content\":{\"type\":\"text\",\"text\":\"Paris\"},\"model\":\"m\"}"), null, caps("sampling")));
        assertText("Found 2 root(s)", call("test_input_required_result_list_roots", answers("roots_request", "{\"roots\":[{\"uri\":\"file:///a\"},{\"uri\":\"file:///b\"}]}"), null, caps("roots")));
    }

    @Test
    void requestStateIsSignedAndTamperingIsRefused() throws Exception {
        String name = "test_input_required_result_tampered_state";
        ToolResult.InputRequired first = (ToolResult.InputRequired) call(name, null, null, caps("elicitation"));
        JsonObject confirmed = answers("confirm", "{\"action\":\"accept\",\"content\":{\"ok\":true}}");
        assertText("integrity-ok: state verified", call(name, confirmed, first.requestState(), caps("elicitation")));
        McpProtocolException e = assertThrows(McpProtocolException.class, () -> call(name, confirmed, first.requestState() + "-TAMPERED", caps("elicitation")));
        assertEquals(-32602, e.code());
        assertEquals(200, e.httpStatus());
    }

    @Test
    void aStateIsNotValidForAnotherTool() throws Exception {
        ToolResult.InputRequired first = (ToolResult.InputRequired) call("test_input_required_result_tampered_state", null, null, caps("elicitation"));
        JsonObject confirmed = answers("confirm", "{\"action\":\"accept\",\"content\":{\"ok\":true}}");
        assertThrows(McpProtocolException.class, () -> call("test_input_required_result_request_state", confirmed, first.requestState(), caps("elicitation")));
    }

    @Test
    void multiRoundChangesTheStateEachRound() throws Exception {
        String name = "test_input_required_result_multi_round";
        ToolResult.InputRequired one = (ToolResult.InputRequired) call(name, null, null, caps("elicitation"));
        assertTrue(one.inputRequests().containsKey("step1"));
        ToolResult.InputRequired two = (ToolResult.InputRequired) call(name, answers("step1", "{\"action\":\"accept\",\"content\":{\"name\":\"Ada\"}}"), one.requestState(), caps("elicitation"));
        assertTrue(two.inputRequests().containsKey("step2"));
        assertTrue(!two.requestState().equals(one.requestState()));
        assertText("Multi-round complete for Ada who likes blue", call(name, answers("step2", "{\"action\":\"accept\",\"content\":{\"color\":\"blue\"}}"), two.requestState(), caps("elicitation")));
    }

    @Test
    void multipleInputsNeedAllAnswersAndTheState() throws Exception {
        String name = "test_input_required_result_multiple_inputs";
        ToolResult.InputRequired first = (ToolResult.InputRequired) call(name, null, null, caps("elicitation", "sampling", "roots"));
        assertEquals(3, first.inputRequests().size());
        JsonObject all = Json.createReader(new StringReader("{\"user_name\":{\"action\":\"accept\",\"content\":{\"name\":\"Ada\"}},\"greeting\":{\"content\":{\"type\":\"text\",\"text\":\"Hi\"}},\"client_roots\":{\"roots\":[]},\"extra\":{}}")).readObject();
        assertText("Name: Ada; Greeting: Hi; Roots: 0", call(name, all, first.requestState(), caps("elicitation", "sampling", "roots")));
        assertTrue(call(name, answers("user_name", "{\"action\":\"accept\",\"content\":{\"name\":\"Ada\"}}"), first.requestState(), caps("elicitation")) instanceof ToolResult.InputRequired);
    }

    @Test
    void capabilitiesAskOnlyForWhatIsDeclared() throws Exception {
        String name = "test_input_required_result_capabilities";
        assertText("No supported capabilities declared", call(name, null, null, caps()));
        ToolResult.InputRequired onlySampling = (ToolResult.InputRequired) call(name, null, null, caps("sampling"));
        assertEquals(java.util.Set.of("sample_input"), onlySampling.inputRequests().keySet());
        ToolResult.InputRequired both = (ToolResult.InputRequired) call(name, null, null, caps("sampling", "elicitation"));
        assertEquals(java.util.Set.of("sample_input", "elicit_input"), both.inputRequests().keySet());
        assertText("capabilities-ok: received sample_input", call(name, answers("sample_input", "{}"), null, caps("sampling")));
    }

    @Test
    void completionIsOffered() throws Exception {
        McpRegistry registry = McpRegistry.of(tools.values(), ConformanceContent.resources().values(), List.of(ConformanceContent.template()), ConformanceContent.prompts().values(), List.of(ConformanceContent.completion()));
        assertTrue(registry.hasCompletions());
        assertEquals(List.of("paris", "park", "party"), ConformanceContent.completion().complete(new eu.hansolo.mcp.api.CompletionCall(eu.hansolo.mcp.api.McpCompletionHandler.Kind.PROMPT, "test_prompt_with_arguments", "arg1", "pa", Map.of(), Json.createObjectBuilder().build(), null, Caller.ANONYMOUS)).values());
    }

    @Test
    void triggerToolsReturnTheMutationMessage() throws Exception {
        assertText("Mutation triggered", call("test_trigger_tool_change", null, null, caps()));
        assertText("Mutation triggered", call("test_trigger_prompt_change", null, null, caps()));
    }

    @Test
    void promptsAndResourcesProduceTheExpectedText() throws Exception {
        var prompts = ConformanceContent.prompts();
        PromptResult.Complete withArgs = (PromptResult.Complete) prompts.get("test_prompt_with_arguments").get(new PromptCall("test_prompt_with_arguments", Map.of("arg1", "a", "arg2", "b"), Json.createObjectBuilder().build(), null, null, null, Caller.ANONYMOUS));
        assertEquals("Prompt with arguments: arg1='a', arg2='b'", withArgs.messages().get(0).content().getString("text"));
        PromptResult.Complete embedded = (PromptResult.Complete) prompts.get("test_prompt_with_embedded_resource").get(new PromptCall("test_prompt_with_embedded_resource", Map.of("resourceUri", "test://x"), Json.createObjectBuilder().build(), null, null, null, Caller.ANONYMOUS));
        assertEquals("resource", embedded.messages().get(0).content().getString("type"));
        assertEquals(2, embedded.messages().size());
        ResourceResult.Complete data = (ResourceResult.Complete) ConformanceContent.template().read(new ResourceCall("test://template/42/data", Map.of("id", "42"), Json.createObjectBuilder().build(), null, null, null, Caller.ANONYMOUS));
        assertEquals("{\"id\":\"42\",\"templateTest\":true,\"data\":\"Data for ID: 42\"}", data.contents().get(0).text());
    }


    // ---- helpers -------------------------------------------------------------------------------------------------

    private ToolResult call(String name, JsonObject inputResponses, String requestState, JsonObject capabilities) throws Exception {
        return tools.get(name).call(new ToolCall(name, Json.createObjectBuilder().build(), capabilities, null, inputResponses, requestState, Caller.ANONYMOUS));
    }

    private static JsonObject caps(String... names) {
        var builder = Json.createObjectBuilder();
        Arrays.stream(names).forEach(n -> builder.add(n, Json.createObjectBuilder()));
        return builder.build();
    }

    private static JsonObject answers(String key, String json) {
        return Json.createObjectBuilder().add(key, Json.createReader(new StringReader(json)).readObject()).build();
    }

    private static void assertText(String expected, ToolResult result) {
        ToolResult.Complete complete = (ToolResult.Complete) result;
        assertEquals(expected, complete.content().getJsonObject(0).getString("text"));
    }

    private JsonObject send(McpRequestHandler handler, String method, JsonObject params, String name) {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.put("MCP-Protocol-Version", "2026-07-28");
        headers.put("Mcp-Method", method);
        if (name != null) {
            headers.put("Mcp-Name", name);
        }
        JsonObject meta = Json.createObjectBuilder().add("io.modelcontextprotocol/protocolVersion", "2026-07-28").add("io.modelcontextprotocol/clientInfo", Json.createObjectBuilder().add("name", "c").add("version", "0")).add("io.modelcontextprotocol/clientCapabilities", Json.createObjectBuilder()).build();
        JsonObject withMeta = Json.createObjectBuilder(params).add("_meta", meta).build();
        String body = Json.createObjectBuilder().add("jsonrpc", "2.0").add("id", 1).add("method", method).add("params", withMeta).build().toString();
        var response = handler.handle(headers::get, new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        assertEquals(200, response.status(), response.body());
        return Json.createReader(new StringReader(response.body())).readObject().getJsonObject("result");
    }
}
