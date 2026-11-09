package eu.hansolo.mcp.server;

import eu.hansolo.mcp.api.Caller;
import eu.hansolo.mcp.api.McpToolHandler;
import eu.hansolo.mcp.api.TaskSupport;
import eu.hansolo.mcp.api.ToolCall;
import eu.hansolo.mcp.api.ToolResult;
import eu.hansolo.mcp.auth.AuthConfig;
import eu.hansolo.mcp.protocol.ErrorCodes;
import eu.hansolo.mcp.tasks.InMemoryTaskStore;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Tasks extension on the wire: announcement, capability gating, headers and the three methods. */
class McpTasksTest {

    private static final String VERSION = "2026-07-28";
    private static final String TASKS   = "io.modelcontextprotocol/tasks";

    private final McpConfig   config   = new McpConfig(null, 65536, 1000L, "t", "1", null);
    private final McpRegistry registry = McpRegistry.of(List.of(new Slow(), new Needed()));


    @Test
    void theExtensionIsAnnouncedOnlyWhenSwitchedOn() {
        assertFalse(result(send(handler(false), "server/discover", null, null, null)).getJsonObject("capabilities").containsKey("extensions"));
        JsonObject announced = result(send(handler(true), "server/discover", null, null, null)).getJsonObject("capabilities").getJsonObject("extensions");
        assertEquals(0, announced.getJsonObject(TASKS).size());
    }

    @Test
    void aClientThatDeclaredTasksGetsATaskAndPollsItToTheResult() throws Exception {
        McpRequestHandler handler = handler(true);
        JsonObject created = result(send(handler, "tools/call", "slow", Json.createObjectBuilder().add("name", "slow"), declared()));
        assertEquals("task", created.getString("resultType"));
        String id = created.getString("taskId");
        JsonObject done = null;
        for (int i = 0; i < 200 && done == null; i++) {
            JsonObject polled = result(send(handler, "tasks/get", id, Json.createObjectBuilder().add("taskId", id), declared()));
            done = polled.getString("status").equals("completed") ? polled : null;
            if (done == null) {
                Thread.sleep(10);
            }
        }
        assertTrue(done != null);
        assertEquals("complete", done.getString("resultType"));
        assertEquals("finished", done.getJsonObject("result").getJsonArray("content").getJsonObject(0).getString("text"));
        assertTrue(done.getJsonObject("_meta").containsKey("io.modelcontextprotocol/serverInfo"));
    }

    @Test
    void aClientWithoutTheExtensionGetsThePlainResultInstead() {
        JsonObject plain = result(send(handler(true), "tools/call", "slow", Json.createObjectBuilder().add("name", "slow"), null));
        assertEquals("complete", plain.getString("resultType"));
        assertEquals("finished", plain.getJsonArray("content").getJsonObject(0).getString("text"));
    }

    @Test
    void withTheExtensionSwitchedOffEveryClientGetsThePlainResultAndTheMethodsAreUnknown() {
        McpRequestHandler handler = handler(false);
        assertEquals("complete", result(send(handler, "tools/call", "slow", Json.createObjectBuilder().add("name", "slow"), declared())).getString("resultType"));
        McpHttpResponse response = send(handler, "tasks/get", "abc", Json.createObjectBuilder().add("taskId", "abc"), declared());
        assertEquals(404, response.status());
        assertEquals(ErrorCodes.METHOD_NOT_FOUND, json(response).getJsonObject("error").getInt("code"));
    }

    @Test
    void theTaskMethodsNeedTheClientToHaveDeclaredTheExtension() {
        McpRequestHandler handler = handler(true);
        for (String method : List.of("tasks/get", "tasks/update", "tasks/cancel")) {
            JsonObjectBuilder params = Json.createObjectBuilder().add("taskId", "abc").add("inputResponses", Json.createObjectBuilder());
            McpHttpResponse response = send(handler, method, "abc", params, null);
            assertEquals(400, response.status(), method);
            assertEquals(ErrorCodes.MISSING_REQUIRED_CLIENT_CAPABILITY, json(response).getJsonObject("error").getInt("code"), method);
        }
    }

    @Test
    void theNameHeaderMustCarryTheTaskId() {
        McpRequestHandler handler = handler(true);
        assertEquals(ErrorCodes.HEADER_MISMATCH, json(send(handler, "tasks/get", "other", Json.createObjectBuilder().add("taskId", "abc"), declared())).getJsonObject("error").getInt("code"));
        assertEquals(ErrorCodes.HEADER_MISMATCH, json(send(handler, "tasks/cancel", null, Json.createObjectBuilder().add("taskId", "abc"), declared())).getJsonObject("error").getInt("code"));
    }

    @Test
    void whitespaceAroundAHeaderValueIsNotPartOfTheValue() {
        McpRequestHandler handler = handler(true);
        JsonObject error = json(send(handler, "tasks/get", "  abc \t", Json.createObjectBuilder().add("taskId", "abc"), declared())).getJsonObject("error");
        assertEquals(ErrorCodes.INVALID_PARAMS, error.getInt("code"));
        assertEquals(ErrorCodes.HEADER_MISMATCH, json(send(handler, "tasks/get", "  abd ", Json.createObjectBuilder().add("taskId", "abc"), declared())).getJsonObject("error").getInt("code"));
    }

    @Test
    void anUnknownTaskIsInvalidParamsAndUpdateNeedsInputResponses() {
        McpRequestHandler handler = handler(true);
        JsonObject error = json(send(handler, "tasks/get", "abc", Json.createObjectBuilder().add("taskId", "abc"), declared())).getJsonObject("error");
        assertEquals(ErrorCodes.INVALID_PARAMS, error.getInt("code"));
        assertEquals("Unknown task", error.getString("message"));
        JsonObject missing = json(send(handler, "tasks/update", "abc", Json.createObjectBuilder().add("taskId", "abc"), declared())).getJsonObject("error");
        assertEquals(ErrorCodes.INVALID_PARAMS, missing.getInt("code"));
    }

    @Test
    void updateAndCancelAnswerWithAnEmptyAck() throws Exception {
        McpRequestHandler handler = handler(true);
        String id = result(send(handler, "tools/call", "slow", Json.createObjectBuilder().add("name", "slow"), declared())).getString("taskId");
        JsonObject ack = result(send(handler, "tasks/cancel", id, Json.createObjectBuilder().add("taskId", id), declared()));
        assertEquals("complete", ack.getString("resultType"));
        assertFalse(ack.containsKey("status"));
        JsonObject updated = result(send(handler, "tasks/update", id, Json.createObjectBuilder().add("taskId", id).add("inputResponses", Json.createObjectBuilder().add("nobody", Json.createObjectBuilder())), declared()));
        assertEquals("complete", updated.getString("resultType"));
    }


    @Test
    void aToolThatRequiresTasksIsRefusedWithoutTheExtensionAndRunsAsATaskWithIt() {
        McpHttpResponse refused = send(handler(true), "tools/call", "needed", Json.createObjectBuilder().add("name", "needed"), null);
        assertEquals(400, refused.status());
        assertEquals(ErrorCodes.MISSING_REQUIRED_CLIENT_CAPABILITY, json(refused).getJsonObject("error").getInt("code"));
        assertEquals(ErrorCodes.MISSING_REQUIRED_CLIENT_CAPABILITY, json(send(handler(false), "tools/call", "needed", Json.createObjectBuilder().add("name", "needed"), declared())).getJsonObject("error").getInt("code"));
        assertEquals("task", result(send(handler(true), "tools/call", "needed", Json.createObjectBuilder().add("name", "needed"), declared())).getString("resultType"));
    }

    @Test
    void theToolListAnnouncesTheDeclaredTaskSupportOnly() {
        var tools = result(send(handler(true), "tools/list", null, null, null)).getJsonArray("tools");
        for (var value : tools) {
            JsonObject tool = value.asJsonObject();
            if (tool.getString("name").equals("needed")) {
                assertEquals("required", tool.getJsonObject("execution").getString("taskSupport"));
            } else {
                assertFalse(tool.containsKey("execution"));
            }
        }
    }

    private static class Needed extends Slow {

        @Override
        public String name() {
            return "needed";
        }

        @Override
        public TaskSupport taskSupport() {
            return TaskSupport.REQUIRED;
        }
    }

    private McpRequestHandler handler(boolean tasksOn) {
        TaskConfig taskConfig = new TaskConfig(tasksOn, 3_600_000L, 2_000L, 1024 * 1024, 100, null, 10_000L, 30_000L, 30_000L, 20L, 60_000L);
        TaskService service = new TaskService(new InMemoryTaskStore(), taskConfig, registry, Clock.systemUTC(), Executors.newVirtualThreadPerTaskExecutor());
        return new McpRequestHandler(registry, config, AuthConfig.disabled(), header -> Caller.ANONYMOUS, new McpNotifications(config), McpExtensions.none(), service);
    }

    private static JsonObject declared() {
        return Json.createObjectBuilder().add("extensions", Json.createObjectBuilder().add(TASKS, Json.createObjectBuilder())).build();
    }

    private static McpHttpResponse send(McpRequestHandler target, String method, String name, JsonObjectBuilder params, JsonObject clientCapabilities) {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.put("MCP-Protocol-Version", VERSION);
        headers.put("Mcp-Method", method);
        if (name != null) {
            headers.put("Mcp-Name", name);
        }
        JsonObjectBuilder all = params == null ? Json.createObjectBuilder() : params;
        JsonObject meta = Json.createObjectBuilder().add("io.modelcontextprotocol/protocolVersion", VERSION).add("io.modelcontextprotocol/clientCapabilities", clientCapabilities == null ? Json.createObjectBuilder().build() : clientCapabilities).build();
        String body = Json.createObjectBuilder().add("jsonrpc", "2.0").add("id", 1).add("method", method).add("params", all.add("_meta", meta)).build().toString();
        return target.handle(headers::get, new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static JsonObject json(McpHttpResponse response) {
        return Json.createReader(new StringReader(response.body())).readObject();
    }

    private static JsonObject result(McpHttpResponse response) {
        assertEquals(200, response.status(), response.body());
        return json(response).getJsonObject("result");
    }


    private static class Slow implements McpToolHandler {

        @Override
        public String name() {
            return "slow";
        }

        @Override
        public String description() {
            return "finishes after a moment, as a task if the client can";
        }

        @Override
        public JsonObject inputSchema() {
            return Json.createObjectBuilder().add("type", "object").build();
        }

        @Override
        public ToolResult call(ToolCall call) {
            return ToolResult.task(ctx -> {
                TimeUnit.MILLISECONDS.sleep(30);
                return ToolResult.text("finished");
            });
        }
    }
}
