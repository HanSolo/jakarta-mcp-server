package eu.hansolo.mcp.server;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import eu.hansolo.mcp.api.Apps;
import eu.hansolo.mcp.api.Caller;
import eu.hansolo.mcp.api.CompletionCall;
import eu.hansolo.mcp.api.CompletionResult;
import eu.hansolo.mcp.api.McpCompletionHandler;
import eu.hansolo.mcp.api.McpPromptHandler;
import eu.hansolo.mcp.api.McpResourceHandler;
import eu.hansolo.mcp.api.McpResourceTemplateHandler;
import eu.hansolo.mcp.api.McpToolHandler;
import eu.hansolo.mcp.api.PromptArgument;
import eu.hansolo.mcp.api.PromptCall;
import eu.hansolo.mcp.api.PromptMessage;
import eu.hansolo.mcp.api.PromptResult;
import eu.hansolo.mcp.api.ResourceCall;
import eu.hansolo.mcp.api.ResourceResult;
import eu.hansolo.mcp.api.ToolCall;
import eu.hansolo.mcp.api.ToolResult;
import eu.hansolo.mcp.auth.AuthConfig;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tools, prompts, resources, templates and completions that come and go while the server runs. */
class McpRegistrationsTest {

    private static final String VERSION = "2026-07-28";
    private static final String BOTH    = "application/json, text/event-stream";

    private final McpConfig         config        = new McpConfig(null, 4096, 0L, "test", "1", null, 0, 20L, 5, 5);
    private final McpNotifications  notifications = new McpNotifications(config);
    private final McpRegistry       registry      = McpRegistry.of(List.of(new Tool("first", "one")));
    private final McpRequestHandler handler       = new McpRequestHandler(registry, config, AuthConfig.disabled(), header -> Caller.ANONYMOUS, notifications);
    private final McpRegistrations  registrations = new McpRegistrations(registry, notifications, AuthConfig.disabled());


    @Test
    void anAddedToolIsListedAndCallable() {
        registrations.addTool(new Tool("second", "two"));
        assertEquals(List.of("first", "second"), toolNames());
        assertEquals("two", callText("second"));
    }

    @Test
    void aRemovedToolIsGoneAndItsCallsAreUnknownTool() {
        assertTrue(registrations.removeTool("first"));
        assertEquals(List.of(), toolNames());
        JsonObject response = json(call("first").body());
        assertEquals(-32602, response.getJsonObject("error").getInt("code"));
        assertFalse(registrations.removeTool("first"));
    }

    @Test
    void replacingATool() {
        registrations.replaceTool(new Tool("first", "changed"));
        assertEquals("changed", callText("first"));
        assertEquals(List.of("first"), toolNames());
        registrations.replaceTool(new Tool("brand-new", "x"));
        assertEquals(List.of("brand-new", "first"), toolNames());
    }

    @Test
    void addingTheSameNameTwiceOrAnInvalidNameIsRefused() {
        assertThrows(IllegalStateException.class, () -> registrations.addTool(new Tool("first", "again")));
        assertThrows(IllegalStateException.class, () -> registrations.addTool(new Tool("bad name!", "x")));
        assertEquals(List.of("first"), toolNames());
    }

    @Test
    void aToolThatNeedsScopesIsRefusedWhileAuthenticationIsOff() {
        Tool scoped = new Tool("scoped", "x") {
            @Override
            public Set<String> requiredScopes() {
                return Set.of("files");
            }
        };
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> registrations.addTool(scoped));
        assertTrue(e.getMessage().contains("mcp.auth.enabled"));
        assertEquals(List.of("first"), toolNames());
    }

    @Test
    void promptsSwitchTheCapabilityOnWithTheFirstOne() {
        assertFalse(capabilities().containsKey("prompts"));
        assertEquals(404, send("prompts/list", "p1", Json.createObjectBuilder().build(), null).status());
        registrations.addPrompt(new Prompt("greet"));
        assertTrue(capabilities().containsKey("prompts"));
        JsonObject listed = json(send("prompts/list", "p2", Json.createObjectBuilder().build(), null).body()).getJsonObject("result");
        assertEquals("greet", listed.getJsonArray("prompts").getJsonObject(0).getString("name"));
        JsonObject got = json(send("prompts/get", "p3", Json.createObjectBuilder().add("name", "greet").build(), "greet").body()).getJsonObject("result");
        assertEquals("hello", got.getJsonArray("messages").getJsonObject(0).getJsonObject("content").getString("text"));
        assertTrue(registrations.removePrompt("greet"));
        assertTrue(capabilities().containsKey("prompts"), "stays on once a prompt was registered, so listening clients keep working");
        assertEquals(0, json(send("prompts/list", "p4", Json.createObjectBuilder().build(), null).body()).getJsonObject("result").getJsonArray("prompts").size());
    }

    @Test
    void subscribersAreToldAboutChangesAndNotAboutNoOps() throws IOException {
        McpHttpResponse stream = send("subscriptions/listen", "7", Json.createObjectBuilder().add("notifications", Json.createObjectBuilder().add("toolsListChanged", true).add("promptsListChanged", true)).build(), null);
        registrations.addPrompt(new Prompt("greet"));   // promptsListChanged is honored only once prompts exist; this one was not honored at listen time
        registrations.addTool(new Tool("second", "two"));
        registrations.removeTool("nope");               // nothing removed, nothing sent
        registrations.removeTool("second");
        registrations.removePrompt("nope");
        notifications.close();
        List<String> methods = new ArrayList<>();
        stream.stream().writeTo(text -> methods.add(json(text).getString("method", "response")));
        assertEquals("notifications/subscriptions/acknowledged", methods.get(0));
        // changes made before the writer looks are coalesced into one notification, as for McpNotifications
        assertEquals(1, methods.stream().filter("notifications/tools/list_changed"::equals).count(), methods.toString());
        assertEquals("response", methods.get(methods.size() - 1));
    }

    @Test
    void aRemovalThatRemovesNothingSendsNothing() throws IOException {
        McpHttpResponse stream = send("subscriptions/listen", "9", Json.createObjectBuilder().add("notifications", Json.createObjectBuilder().add("toolsListChanged", true)).build(), null);
        assertFalse(registrations.removeTool("nope"));
        assertFalse(registrations.removePrompt("nope"));
        notifications.close();
        List<String> methods = new ArrayList<>();
        stream.stream().writeTo(text -> methods.add(json(text).getString("method", "response")));
        assertEquals(List.of("notifications/subscriptions/acknowledged", "response"), methods);
    }

    @Test
    void promptNotificationsAreHonoredWhenPromptsAreRegisteredBeforeListening() throws IOException {
        registrations.addPrompt(new Prompt("greet"));
        McpHttpResponse stream = send("subscriptions/listen", "8", Json.createObjectBuilder().add("notifications", Json.createObjectBuilder().add("promptsListChanged", true)).build(), null);
        registrations.replacePrompt(new Prompt("greet"));
        registrations.removePrompt("greet");
        notifications.close();
        List<String> methods = new ArrayList<>();
        stream.stream().writeTo(text -> methods.add(json(text).getString("method", "response")));
        assertEquals(1, methods.stream().filter("notifications/prompts/list_changed"::equals).count(), methods.toString());
    }

    @Test
    void changesWhileCallsAreRunningAreSafe() throws Exception {
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            int id = t;
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 200; i++) {
                    registrations.replaceTool(new Tool("t" + id + "-" + (i % 5), "x"));
                    registrations.removeTool("t" + id + "-" + ((i + 2) % 5));
                    registry.all();
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        assertTrue(registry.all().stream().allMatch(tool -> tool.name().startsWith("t") || tool.name().equals("first")));
    }

    @Test
    void resourcesSwitchTheCapabilityOnWithTheFirstOne() {
        assertFalse(capabilities().containsKey("resources"));
        assertEquals(404, send("resources/list", "r0", Json.createObjectBuilder().build(), null).status());
        registrations.addResource(new Res("mem://one", "first"));
        assertTrue(capabilities().containsKey("resources"));
        JsonObject listed = json(send("resources/list", "r1", Json.createObjectBuilder().build(), null).body()).getJsonObject("result");
        assertEquals("mem://one", listed.getJsonArray("resources").getJsonObject(0).getString("uri"));
        assertEquals("first", readText("mem://one"));
        assertTrue(registrations.removeResource("mem://one"));
        assertTrue(capabilities().containsKey("resources"), "stays on once a resource was registered, so listening clients keep working");
        assertEquals(0, json(send("resources/list", "r2", Json.createObjectBuilder().build(), null).body()).getJsonObject("result").getJsonArray("resources").size());
        assertEquals(-32602, json(send("resources/read", "r3", Json.createObjectBuilder().add("uri", "mem://one").build(), "mem://one").body()).getJsonObject("error").getInt("code"));
        assertFalse(registrations.removeResource("mem://one"));
    }

    @Test
    void aResourceCanBeReplacedButNotAddedTwiceOrWithABadUri() {
        registrations.addResource(new Res("mem://one", "first"));
        assertThrows(IllegalStateException.class, () -> registrations.addResource(new Res("mem://one", "again")));
        assertThrows(IllegalStateException.class, () -> registrations.addResource(new Res("no-scheme", "x")));
        registrations.replaceResource(new Res("mem://one", "changed"));
        assertEquals("changed", readText("mem://one"));
    }

    @Test
    void aResourceThatNeedsScopesIsRefusedWhileAuthenticationIsOff() {
        Res scoped = new Res("mem://scoped", "x") {
            @Override
            public Set<String> requiredScopes() {
                return Set.of("files");
            }
        };
        assertTrue(assertThrows(IllegalStateException.class, () -> registrations.addResource(scoped)).getMessage().contains("mcp.auth.enabled"));
        assertFalse(capabilities().containsKey("resources"));
    }

    @Test
    void aTemplateServesItsUrisAndGoesAway() {
        registrations.addResourceTemplate(new Templ("mem://item/{id}"));
        JsonObject listed = json(send("resources/templates/list", "t1", Json.createObjectBuilder().build(), null).body()).getJsonObject("result");
        assertEquals("mem://item/{id}", listed.getJsonArray("resourceTemplates").getJsonObject(0).getString("uriTemplate"));
        assertEquals("item 42", readText("mem://item/42"));
        assertThrows(IllegalStateException.class, () -> registrations.addResourceTemplate(new Templ("mem://item/{id}")));
        assertThrows(IllegalStateException.class, () -> registrations.addResourceTemplate(new Templ("mem://item/{id")));
        assertTrue(registrations.removeResourceTemplate("mem://item/{id}"));
        assertFalse(registrations.removeResourceTemplate("mem://item/{id}"));
        assertEquals(-32602, json(send("resources/read", "t2", Json.createObjectBuilder().add("uri", "mem://item/42").build(), "mem://item/42").body()).getJsonObject("error").getInt("code"));
    }

    @Test
    void aCompletionSwitchesTheCapabilityOnAndAnswers() {
        registrations.addPrompt(new Prompt("greet") {
            @Override
            public List<PromptArgument> arguments() {
                return List.of(new PromptArgument("language", null, null, false));
            }
        });
        assertFalse(capabilities().containsKey("completions"));
        assertEquals(404, complete("greet", "language", "d").status());
        registrations.addCompletion(new Completer(McpCompletionHandler.Kind.PROMPT, "greet", "language", "de", "dk", "en"));
        assertTrue(capabilities().containsKey("completions"));
        assertEquals(List.of("de", "dk"), completionValues(complete("greet", "language", "d")));
        registrations.replaceCompletion(new Completer(McpCompletionHandler.Kind.PROMPT, "greet", "language", "fr"));
        assertEquals(List.of("fr"), completionValues(complete("greet", "language", "")));
        assertTrue(registrations.removeCompletion(McpCompletionHandler.Kind.PROMPT, "greet", "language"));
        assertFalse(registrations.removeCompletion(McpCompletionHandler.Kind.PROMPT, "greet", "language"));
        assertEquals(List.of(), completionValues(complete("greet", "language", "")));
    }

    @Test
    void aCompletionNeedsAnExistingTargetAndArgumentAndIsUnique() {
        registrations.addResourceTemplate(new Templ("mem://item/{id}"));
        assertTrue(assertThrows(IllegalStateException.class, () -> registrations.addCompletion(new Completer(McpCompletionHandler.Kind.PROMPT, "nope", "x", "a"))).getMessage().contains("no such prompt"));
        assertTrue(assertThrows(IllegalStateException.class, () -> registrations.addCompletion(new Completer(McpCompletionHandler.Kind.RESOURCE_TEMPLATE, "mem://item/{id}", "other", "a"))).getMessage().contains("unknown argument"));
        registrations.addCompletion(new Completer(McpCompletionHandler.Kind.RESOURCE_TEMPLATE, "mem://item/{id}", "id", "1", "2"));
        assertThrows(IllegalStateException.class, () -> registrations.addCompletion(new Completer(McpCompletionHandler.Kind.RESOURCE_TEMPLATE, "mem://item/{id}", "id", "3")));
    }

    @Test
    void removingATemplateOrPromptDropsItsCompletions() {
        registrations.addResourceTemplate(new Templ("mem://item/{id}"));
        registrations.addCompletion(new Completer(McpCompletionHandler.Kind.RESOURCE_TEMPLATE, "mem://item/{id}", "id", "1"));
        registrations.removeResourceTemplate("mem://item/{id}");
        registrations.addResourceTemplate(new Templ("mem://item/{id}"));
        assertTrue(registry.findCompletion(McpCompletionHandler.Kind.RESOURCE_TEMPLATE, "mem://item/{id}", "id").isEmpty(), "a new template of the same name must not inherit it");
        registrations.addPrompt(new Prompt("greet") {
            @Override
            public List<PromptArgument> arguments() {
                return List.of(new PromptArgument("language", null, null, false));
            }
        });
        registrations.addCompletion(new Completer(McpCompletionHandler.Kind.PROMPT, "greet", "language", "de"));
        registrations.removePrompt("greet");
        assertTrue(registry.findCompletion(McpCompletionHandler.Kind.PROMPT, "greet", "language").isEmpty());
    }

    @Test
    void aResourceThatAToolPointsAtCannotBeRemoved() {
        registrations.addResource(new Res("ui://page", "<html></html>"));
        registrations.addTool(new Tool("show", "x") {
            @Override
            public JsonObject meta() {
                return Apps.toolMeta("ui://page");
            }
        });
        assertTrue(assertThrows(IllegalStateException.class, () -> registrations.removeResource("ui://page")).getMessage().contains("show"));
        assertEquals("<html></html>", readText("ui://page"));
        assertTrue(registrations.removeTool("show"));
        assertTrue(registrations.removeResource("ui://page"));
    }

    @Test
    void aToolCanOnlyPointAtAResourceThatWasAddedBefore() {
        Tool bound = new Tool("show", "x") {
            @Override
            public JsonObject meta() {
                return Apps.toolMeta("ui://late");
            }
        };
        assertThrows(IllegalStateException.class, () -> registrations.addTool(bound));
        registrations.addResource(new Res("ui://late", "<html></html>"));
        registrations.addTool(bound);
        assertEquals(List.of("first", "show"), toolNames());
    }

    @Test
    void aTemplateThatServesTheUiResourceOfAToolCannotBeRemovedEither() {
        registrations.addResourceTemplate(new Templ("ui://{page}"));
        registrations.addTool(new Tool("show", "x") {
            @Override
            public JsonObject meta() {
                return Apps.toolMeta("ui://home");
            }
        });
        assertThrows(IllegalStateException.class, () -> registrations.removeResourceTemplate("ui://{page}"));
        registrations.addResource(new Res("ui://home", "<html></html>"));
        assertTrue(registrations.removeResourceTemplate("ui://{page}"), "a fixed resource still serves it");
    }

    @Test
    void resourceChangesAreToldToSubscribersAndNoOpsAreNot() throws IOException {
        registrations.addResource(new Res("mem://one", "first"));
        McpHttpResponse stream = send("subscriptions/listen", "10", Json.createObjectBuilder().add("notifications", Json.createObjectBuilder().add("resourcesListChanged", true)).build(), null);
        registrations.removeResource("mem://nope");
        registrations.removeResourceTemplate("nope");
        registrations.addResourceTemplate(new Templ("mem://item/{id}"));
        registrations.addCompletion(new Completer(McpCompletionHandler.Kind.RESOURCE_TEMPLATE, "mem://item/{id}", "id", "1"));   // no list, nothing sent
        notifications.close();
        List<String> methods = new ArrayList<>();
        stream.stream().writeTo(text -> methods.add(json(text).getString("method", "response")));
        assertEquals(1, methods.stream().filter("notifications/resources/list_changed"::equals).count(), methods.toString());
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private List<String> toolNames() {
        JsonObject result = json(send("tools/list", "t", Json.createObjectBuilder().build(), null).body()).getJsonObject("result");
        return result.getJsonArray("tools").getValuesAs(JsonObject.class).stream().map(t -> t.getString("name")).toList();
    }

    private JsonObject capabilities() {
        return json(send("server/discover", "d", Json.createObjectBuilder().build(), null).body()).getJsonObject("result").getJsonObject("capabilities");
    }

    private McpHttpResponse call(String name) {
        return send("tools/call", "c", Json.createObjectBuilder().add("name", name).add("arguments", Json.createObjectBuilder()).build(), name);
    }

    private String callText(String name) {
        return json(call(name).body()).getJsonObject("result").getJsonArray("content").getJsonObject(0).getString("text");
    }

    private McpHttpResponse send(String method, String id, JsonObject params, String name) {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.put("MCP-Protocol-Version", VERSION);
        headers.put("Mcp-Method", method);
        headers.put("Accept", BOTH);
        if (name != null) {
            headers.put("Mcp-Name", name);
        }
        JsonObject meta = Json.createObjectBuilder().add("io.modelcontextprotocol/protocolVersion", VERSION).add("io.modelcontextprotocol/clientCapabilities", Json.createObjectBuilder()).build();
        String body = Json.createObjectBuilder().add("jsonrpc", "2.0").add("id", 1).add("method", method).add("params", Json.createObjectBuilder(params).add("_meta", meta)).build().toString();
        return handler.handle(headers::get, new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static JsonObject json(String text) {
        return Json.createReader(new StringReader(text)).readObject();
    }

    private String readText(String uri) {
        return json(send("resources/read", "rd", Json.createObjectBuilder().add("uri", uri).build(), uri).body()).getJsonObject("result").getJsonArray("contents").getJsonObject(0).getString("text");
    }

    private McpHttpResponse complete(String prompt, String argument, String typed) {
        return send("completion/complete", "cc", Json.createObjectBuilder()
                .add("ref", Json.createObjectBuilder().add("type", "ref/prompt").add("name", prompt))
                .add("argument", Json.createObjectBuilder().add("name", argument).add("value", typed)).build(), null);
    }

    private static List<String> completionValues(McpHttpResponse response) {
        return json(response.body()).getJsonObject("result").getJsonObject("completion").getJsonArray("values").getValuesAs(jakarta.json.JsonString.class).stream().map(jakarta.json.JsonString::getString).toList();
    }

    private static class Res implements McpResourceHandler {

        private final String uri;
        private final String text;


        Res(String uri, String text) {
            this.uri  = uri;
            this.text = text;
        }


        @Override
        public String uri() {
            return uri;
        }

        @Override
        public String name() {
            return uri;
        }

        @Override
        public String mimeType() {
            return "text/plain";
        }

        @Override
        public ResourceResult read(ResourceCall call) {
            return ResourceResult.text(uri, "text/plain", text);
        }
    }

    private static final class Templ implements McpResourceTemplateHandler {

        private final String template;


        Templ(String template) {
            this.template = template;
        }


        @Override
        public String uriTemplate() {
            return template;
        }

        @Override
        public String name() {
            return "template";
        }

        @Override
        public ResourceResult read(ResourceCall call) {
            return ResourceResult.text(call.uri(), "text/plain", "item " + call.variables().values().iterator().next());
        }
    }

    private static final class Completer implements McpCompletionHandler {

        private final Kind         kind;
        private final String       target;
        private final String       argument;
        private final List<String> candidates;


        Completer(Kind kind, String target, String argument, String... candidates) {
            this.kind       = kind;
            this.target     = target;
            this.argument   = argument;
            this.candidates = List.of(candidates);
        }


        @Override
        public Kind kind() {
            return kind;
        }

        @Override
        public String target() {
            return target;
        }

        @Override
        public String argument() {
            return argument;
        }

        @Override
        public CompletionResult complete(CompletionCall call) {
            return CompletionResult.matching(candidates, call.value());
        }
    }

    private static class Tool implements McpToolHandler {

        private final String name;
        private final String reply;


        Tool(String name, String reply) {
            this.name  = name;
            this.reply = reply;
        }


        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return name;
        }

        @Override
        public JsonObject inputSchema() {
            return Json.createObjectBuilder().add("type", "object").add("additionalProperties", false).build();
        }

        @Override
        public ToolResult call(ToolCall call) {
            return ToolResult.text(reply);
        }
    }

    private static class Prompt implements McpPromptHandler {

        private final String name;


        Prompt(String name) {
            this.name = name;
        }


        @Override
        public String name() {
            return name;
        }

        @Override
        public PromptResult get(PromptCall call) {
            return PromptResult.of(PromptMessage.user("hello"));
        }
    }
}
