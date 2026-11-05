package eu.hansolo.mcp.server;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import eu.hansolo.mcp.api.Caller;
import eu.hansolo.mcp.api.CompletionCall;
import eu.hansolo.mcp.api.CompletionResult;
import eu.hansolo.mcp.api.McpCompletionHandler;
import eu.hansolo.mcp.api.McpPromptHandler;
import eu.hansolo.mcp.api.McpResourceTemplateHandler;
import eu.hansolo.mcp.api.PromptArgument;
import eu.hansolo.mcp.api.PromptCall;
import eu.hansolo.mcp.api.PromptMessage;
import eu.hansolo.mcp.api.PromptResult;
import eu.hansolo.mcp.api.ResourceCall;
import eu.hansolo.mcp.api.ResourceResult;
import eu.hansolo.mcp.auth.AuthConfig;
import eu.hansolo.mcp.protocol.ErrorCodes;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpCompletionTest {

    private static final String VERSION = "2026-07-28";

    private final AtomicReference<CompletionCall> seen = new AtomicReference<>();
    private final McpConfig                       config = new McpConfig(null, 65536, 1000L, "t", "1", null);
    private final McpRegistry                     registry = McpRegistry.of(List.of(), List.of(), List.of(new Greeting()), List.of(new Review(), new Secret()), List.of(new Language(seen), new Name(), new Many(), new Broken()));
    private final McpRequestHandler               handler = new McpRequestHandler(registry, config);

    @Test
    void discoverAdvertisesTheCapabilityOnlyWhenSomethingCanComplete() {
        assertTrue(result(send(handler, "server/discover", null)).getJsonObject("capabilities").containsKey("completions"));
        McpRequestHandler none = new McpRequestHandler(McpRegistry.of(List.of(), List.of(), List.of(), List.of(new Review())), config);
        assertFalse(result(send(none, "server/discover", null)).getJsonObject("capabilities").containsKey("completions"));
        assertEquals(404, send(none, "completion/complete", prompt("review", "language", "p")).status());
    }

    @Test
    void completesPromptArgumentsWithTotalAndRelevanceOrder() {
        JsonObject completion = result(send(handler, "completion/complete", prompt("review", "language", "py"))).getJsonObject("completion");
        assertEquals(List.of("python", "pypy"), strings(completion.getJsonArray("values")));
        assertEquals(2, completion.getInt("total"));
        assertFalse(completion.getBoolean("hasMore"));
        assertEquals("complete", result(send(handler, "completion/complete", prompt("review", "language", "py"))).getString("resultType"));
    }

    @Test
    void passesTheTypedValueAndContextToTheHandler() {
        JsonObjectBuilder params = prompt("review", "language", "j");
        params.add("context", Json.createObjectBuilder().add("arguments", Json.createObjectBuilder().add("framework", "spring")));
        result(send(handler, "completion/complete", params));
        CompletionCall call = seen.get();
        assertEquals("j", call.value());
        assertEquals("review", call.target());
        assertEquals(Map.of("framework", "spring"), call.contextArguments());
        assertEquals(McpCompletionHandler.Kind.PROMPT, call.kind());
    }

    @Test
    void completesTemplateVariablesByTemplateString() {
        JsonObject params = Json.createObjectBuilder().add("ref", Json.createObjectBuilder().add("type", "ref/resource").add("uri", "greeting://{name}")).add("argument", Json.createObjectBuilder().add("name", "name").add("value", "A")).build();
        JsonObject completion = result(send(handler, "completion/complete", Json.createObjectBuilder(params))).getJsonObject("completion");
        assertEquals(List.of("Ada", "Alan"), strings(completion.getJsonArray("values")));
    }

    @Test
    void anArgumentWithoutAHandlerCompletesToNothing() {
        JsonObject completion = result(send(handler, "completion/complete", prompt("review", "framework", "x"))).getJsonObject("completion");
        assertEquals(0, completion.getJsonArray("values").size());
        assertEquals(0, completion.getInt("total"));
        assertFalse(completion.getBoolean("hasMore"));
    }

    @Test
    void anOverlongSuggestionListIsCutAtOneHundred() {
        JsonObject completion = result(send(handler, "completion/complete", prompt("review", "many", ""))).getJsonObject("completion");
        assertEquals(100, completion.getJsonArray("values").size());
        assertEquals("v0", completion.getJsonArray("values").getString(0));
        assertTrue(completion.getBoolean("hasMore"));
    }

    @Test
    void badReferencesAndArgumentsAreInvalidParams() {
        assertEquals(ErrorCodes.INVALID_PARAMS, error(send(handler, "completion/complete", prompt("nope", "language", ""))));
        assertEquals(ErrorCodes.INVALID_PARAMS, error(send(handler, "completion/complete", prompt("review", "nonexistent", ""))));
        JsonObject unknownType = Json.createObjectBuilder().add("ref", Json.createObjectBuilder().add("type", "ref/other").add("name", "x")).add("argument", Json.createObjectBuilder().add("name", "a").add("value", "")).build();
        assertEquals(ErrorCodes.INVALID_PARAMS, error(send(handler, "completion/complete", Json.createObjectBuilder(unknownType))));
        JsonObject unknownTemplate = Json.createObjectBuilder().add("ref", Json.createObjectBuilder().add("type", "ref/resource").add("uri", "file:///{x}")).add("argument", Json.createObjectBuilder().add("name", "x").add("value", "")).build();
        assertEquals(ErrorCodes.INVALID_PARAMS, error(send(handler, "completion/complete", Json.createObjectBuilder(unknownTemplate))));
        assertEquals(ErrorCodes.INVALID_PARAMS, error(send(handler, "completion/complete", null)));
        assertEquals(ErrorCodes.INVALID_PARAMS, error(send(handler, "completion/complete", prompt("review", "language", "x".repeat(5000)))));
        JsonObjectBuilder badContext = prompt("review", "language", "");
        badContext.add("context", Json.createObjectBuilder().add("arguments", Json.createObjectBuilder().add("framework", 5)));
        assertEquals(ErrorCodes.INVALID_PARAMS, error(send(handler, "completion/complete", badContext)));
    }

    @Test
    void aFailingHandlerIsAnInternalErrorWithoutDetails() {
        McpHttpResponse response = send(handler, "completion/complete", prompt("review", "broken", "x"));
        assertEquals(500, response.status());
        assertFalse(response.body().contains("secret detail"));
    }

    @Test
    void scopesOfThePromptAndTheHandlerApply() {
        AuthConfig auth = new AuthConfig(true, "https://mcp.example.com/mcp", "https://auth.example.com", "https://auth.example.com/jwks", null, List.of(), List.of("mcp:tools", "admin"), List.of("mcp:tools"), 60, 300, 30, false);
        McpRequestHandler secured = new McpRequestHandler(registry, config, auth, header -> new Caller("u", Set.of("mcp:tools"), Json.createObjectBuilder().build()));
        McpHttpResponse forPrompt = send(secured, "completion/complete", prompt("secret", "topic", ""));
        assertEquals(403, forPrompt.status());
        assertTrue(forPrompt.headers().get("WWW-Authenticate").contains("scope=\"admin\""));
        assertEquals(200, send(secured, "completion/complete", prompt("review", "language", "p")).status());
    }

    @Test
    void registryRejectsCompletionsForThingsThatDoNotExist() {
        assertThrows(IllegalStateException.class, () -> McpRegistry.of(List.of(), List.of(), List.of(), List.of(new Review()), List.of(new Fake(McpCompletionHandler.Kind.PROMPT, "missing", "language"))));
        assertThrows(IllegalStateException.class, () -> McpRegistry.of(List.of(), List.of(), List.of(), List.of(new Review()), List.of(new Fake(McpCompletionHandler.Kind.PROMPT, "review", "nope"))));
        assertThrows(IllegalStateException.class, () -> McpRegistry.of(List.of(), List.of(), List.of(), List.of(new Review()), List.of(new Fake(McpCompletionHandler.Kind.PROMPT, "review", "language"), new Fake(McpCompletionHandler.Kind.PROMPT, "review", "language"))));
        assertThrows(IllegalStateException.class, () -> McpRegistry.of(List.of(), List.of(), List.of(new Greeting()), List.of(), List.of(new Fake(McpCompletionHandler.Kind.RESOURCE_TEMPLATE, "greeting://{name}", "other"))));
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private static JsonObjectBuilder prompt(String name, String argument, String value) {
        return Json.createObjectBuilder().add("ref", Json.createObjectBuilder().add("type", "ref/prompt").add("name", name)).add("argument", Json.createObjectBuilder().add("name", argument).add("value", value));
    }

    private static List<String> strings(JsonArray array) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            result.add(array.getString(i));
        }
        return result;
    }

    private static int error(McpHttpResponse response) {
        return json(response).getJsonObject("error").getInt("code");
    }

    private static McpHttpResponse send(McpRequestHandler target, String method, JsonObjectBuilder params) {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.put("MCP-Protocol-Version", VERSION);
        headers.put("Mcp-Method", method);
        JsonObjectBuilder all = params == null ? Json.createObjectBuilder() : params;
        JsonObject meta = Json.createObjectBuilder().add("io.modelcontextprotocol/protocolVersion", VERSION).add("io.modelcontextprotocol/clientCapabilities", Json.createObjectBuilder()).build();
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


    private static class Fake implements McpCompletionHandler {

        private final Kind   kind;
        private final String target;
        private final String argument;


        Fake(Kind kind, String target, String argument) {
            this.kind     = kind;
            this.target   = target;
            this.argument = argument;
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
        public CompletionResult complete(CompletionCall call) throws Exception {
            return CompletionResult.empty();
        }
    }


    private static final class Language extends Fake {

        private final AtomicReference<CompletionCall> seen;


        Language(AtomicReference<CompletionCall> seen) {
            super(Kind.PROMPT, "review", "language");
            this.seen = seen;
        }


        @Override
        public CompletionResult complete(CompletionCall call) {
            seen.set(call);
            return CompletionResult.matching(List.of("python", "pypy", "java", "javascript"), call.value());
        }
    }


    private static final class Name extends Fake {

        Name() {
            super(Kind.RESOURCE_TEMPLATE, "greeting://{name}", "name");
        }


        @Override
        public CompletionResult complete(CompletionCall call) {
            return CompletionResult.matching(List.of("Ada", "Alan", "Grace"), call.value());
        }
    }


    private static final class Many extends Fake {

        Many() {
            super(Kind.PROMPT, "review", "many");
        }


        @Override
        public CompletionResult complete(CompletionCall call) {
            List<String> values = new ArrayList<>();
            for (int i = 0; i < 150; i++) {
                values.add("v" + i);
            }
            return CompletionResult.of(values);
        }
    }


    private static final class Broken extends Fake {

        Broken() {
            super(Kind.PROMPT, "review", "broken");
        }


        @Override
        public CompletionResult complete(CompletionCall call) {
            throw new IllegalStateException("secret detail");
        }
    }


    private static final class Greeting implements McpResourceTemplateHandler {

        @Override
        public String uriTemplate() {
            return "greeting://{name}";
        }

        @Override
        public String name() {
            return "greeting";
        }

        @Override
        public ResourceResult read(ResourceCall call) {
            return ResourceResult.text(call.uri(), "text/plain", "hi");
        }
    }


    private static final class Review implements McpPromptHandler {

        @Override
        public String name() {
            return "review";
        }

        @Override
        public List<PromptArgument> arguments() {
            return List.of(PromptArgument.required("language", "Language"), PromptArgument.optional("framework", "Framework"), PromptArgument.optional("many", "Many"), PromptArgument.optional("broken", "Broken"));
        }

        @Override
        public PromptResult get(PromptCall call) {
            return PromptResult.of(PromptMessage.user("review"));
        }
    }


    private static final class Secret implements McpPromptHandler {

        @Override
        public String name() {
            return "secret";
        }

        @Override
        public List<PromptArgument> arguments() {
            return List.of(PromptArgument.optional("topic", "Topic"));
        }

        @Override
        public Set<String> requiredScopes() {
            return Set.of("admin");
        }

        @Override
        public PromptResult get(PromptCall call) {
            return PromptResult.of(PromptMessage.user("secret"));
        }
    }
}
