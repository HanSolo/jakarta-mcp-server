package eu.hansolo.mcp.tools;

import jakarta.json.Json;
import eu.hansolo.mcp.api.Caller;
import eu.hansolo.mcp.api.McpParam;
import eu.hansolo.mcp.api.McpPrompt;
import eu.hansolo.mcp.api.McpPromptHandler;
import eu.hansolo.mcp.api.McpResource;
import eu.hansolo.mcp.api.McpResourceHandler;
import eu.hansolo.mcp.api.PromptCall;
import eu.hansolo.mcp.api.PromptMessage;
import eu.hansolo.mcp.api.PromptResult;
import eu.hansolo.mcp.api.ResourceCall;
import eu.hansolo.mcp.api.ResourceContent;
import eu.hansolo.mcp.api.ResourceResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnnotatedResourcesPromptsTest {

    public record Info(String name, int answer) {
    }


    public static class Sample {

        @McpResource(uri = "config://info", description = "Info", scopes = {"cfg:read"})
        public Info info() {
            return new Info("x", 42);
        }

        @McpResource(uriTemplate = "greeting://{name}", name = "greet", mimeType = "text/plain")
        public String greeting(String name, Caller caller) {
            return "Hello, " + name + " from " + caller.subject();
        }

        @McpResource(uri = "bin://data")
        public byte[] data() {
            return new byte[]{9};
        }

        @McpResource(uri = "multi://x")
        public List<ResourceContent> multi() {
            return List.of(ResourceContent.text("multi://x/a", "text/plain", "a"), ResourceContent.text("multi://x/b", "text/plain", "b"));
        }

        @McpResource(uri = "ask://x")
        public ResourceResult ask(ResourceCall call) {
            return ResourceResult.inputRequired(null, "s:" + call.uri());
        }

        @McpPrompt(description = "Reviews code", scopes = {"p:read"})
        public String review(@McpParam(description = "The code") String code, Optional<String> language, @McpParam(required = false) String tone) {
            return "Review " + language.orElse("any") + "/" + tone + ": " + code;
        }

        @McpPrompt(name = "dialog")
        public List<PromptMessage> conversation(String topic) {
            return List.of(PromptMessage.user("Tell me about " + topic), PromptMessage.assistant("Sure"));
        }

        @McpPrompt
        public PromptResult wrapped(PromptCall call) {
            return PromptResult.of("desc", List.of(PromptMessage.user("n=" + call.arguments().size())));
        }
    }


    private final ResourceScanner.Found found = ResourceScanner.scan(Sample.class, BeanSource.of(new Sample()));


    private ResourceCall call(String uri, Map<String, String> variables) {
        return new ResourceCall(uri, variables, Json.createObjectBuilder().build(), null, null, null, new Caller("alice", Set.of(), Json.createObjectBuilder().build()));
    }

    private McpResourceHandler resource(String uri) {
        return found.resources().stream().filter(r -> r.uri().equals(uri)).findFirst().orElseThrow();
    }

    private McpPromptHandler prompt(String name) {
        return PromptScanner.scan(Sample.class, BeanSource.of(new Sample())).stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void splitsFixedResourcesFromTemplates() {
        assertEquals(4, found.resources().size());
        assertEquals(List.of("greeting://{name}"), found.templates().stream().map(t -> t.uriTemplate()).toList());
        assertEquals("greet", found.templates().get(0).name());
    }

    @Test
    void recordResultsBecomeJsonTextAndKeepScopesAndDescription() throws Exception {
        McpResourceHandler info = resource("config://info");
        assertEquals("info", info.name());
        assertEquals("Info", info.description());
        assertEquals("application/json", info.mimeType());
        assertEquals(Set.of("cfg:read"), info.requiredScopes());
        ResourceResult.Complete result = (ResourceResult.Complete) info.read(call("config://info", Map.of()));
        assertEquals("{\"name\":\"x\",\"answer\":42}", result.contents().get(0).text());
        assertEquals("application/json", result.contents().get(0).mimeType());
    }

    @Test
    void templateVariablesAndCallerAreInjected() throws Exception {
        ResourceResult.Complete result = (ResourceResult.Complete) found.templates().get(0).read(call("greeting://Bo", Map.of("name", "Bo")));
        assertEquals("Hello, Bo from alice", result.contents().get(0).text());
        assertEquals("greeting://Bo", result.contents().get(0).uri());
        assertEquals("text/plain", result.contents().get(0).mimeType());
    }

    @Test
    void byteArraysBecomeBlobsAndListsOfContentPassThrough() throws Exception {
        ResourceResult.Complete data = (ResourceResult.Complete) resource("bin://data").read(call("bin://data", Map.of()));
        assertEquals("CQ==", data.contents().get(0).blob());
        assertEquals("application/octet-stream", data.contents().get(0).mimeType());
        ResourceResult.Complete multi = (ResourceResult.Complete) resource("multi://x").read(call("multi://x", Map.of()));
        assertEquals(2, multi.contents().size());
    }

    @Test
    void resultTypesPassThrough() throws Exception {
        ResourceResult.InputRequired ask = (ResourceResult.InputRequired) resource("ask://x").read(call("ask://x", Map.of()));
        assertEquals("s:ask://x", ask.requestState());
    }

    @Test
    void misconfiguredResourcesFailAtScanTime() {
        assertTrue(assertThrows(IllegalStateException.class, () -> ResourceScanner.scan(Neither.class, BeanSource.of(new Neither()))).getMessage().contains("exactly one of uri and uriTemplate"));
        assertTrue(assertThrows(IllegalStateException.class, () -> ResourceScanner.scan(Both.class, BeanSource.of(new Both()))).getMessage().contains("exactly one"));
        assertTrue(assertThrows(IllegalStateException.class, () -> ResourceScanner.scan(WrongVariable.class, BeanSource.of(new WrongVariable()))).getMessage().contains("parameter 'other'"));
        assertTrue(assertThrows(IllegalStateException.class, () -> ResourceScanner.scan(NotString.class, BeanSource.of(new NotString()))).getMessage().contains("strings"));
        assertTrue(assertThrows(IllegalStateException.class, () -> ResourceScanner.scan(Voided.class, BeanSource.of(new Voided()))).getMessage().contains("must return a value"));
        assertTrue(assertThrows(IllegalStateException.class, () -> ResourceScanner.scan(BadTemplate.class, BeanSource.of(new BadTemplate()))).getMessage().contains("BadTemplate.t"));
    }

    @Test
    void promptArgumentsComeFromParametersWithRequiredness() {
        McpPromptHandler review = prompt("review");
        assertEquals(Set.of("p:read"), review.requiredScopes());
        assertEquals(3, review.arguments().size());
        assertEquals("code", review.arguments().get(0).name());
        assertTrue(review.arguments().get(0).required());
        assertEquals("The code", review.arguments().get(0).description());
        assertFalse(review.arguments().get(1).required());
        assertFalse(review.arguments().get(2).required());
    }

    @Test
    void promptsMapStringsMessagesAndResults() throws Exception {
        PromptCall reviewCall = new PromptCall("review", Map.of("code", "x"), Json.createObjectBuilder().build(), null, null, null, Caller.ANONYMOUS);
        PromptResult.Complete review = (PromptResult.Complete) prompt("review").get(reviewCall);
        assertEquals("Review any/null: x", review.messages().get(0).content().getString("text"));
        PromptResult.Complete dialog = (PromptResult.Complete) prompt("dialog").get(new PromptCall("dialog", Map.of("topic", "t"), Json.createObjectBuilder().build(), null, null, null, Caller.ANONYMOUS));
        assertEquals(List.of("user", "assistant"), dialog.messages().stream().map(PromptMessage::role).toList());
        PromptResult.Complete wrapped = (PromptResult.Complete) prompt("wrapped").get(new PromptCall("wrapped", Map.of(), Json.createObjectBuilder().build(), null, null, null, Caller.ANONYMOUS));
        assertEquals("desc", wrapped.description());
        assertEquals("n=0", wrapped.messages().get(0).content().getString("text"));
        assertNull(prompt("wrapped").title());
    }

    @Test
    void misconfiguredPromptsFailAtScanTime() {
        assertTrue(assertThrows(IllegalStateException.class, () -> PromptScanner.scan(IntArgument.class, BeanSource.of(new IntArgument()))).getMessage().contains("strings"));
        assertTrue(assertThrows(IllegalStateException.class, () -> PromptScanner.scan(BadReturn.class, BeanSource.of(new BadReturn()))).getMessage().contains("unsupported prompt return type"));
    }


    public static class Neither {

        @McpResource
        public String a() {
            return "";
        }
    }


    public static class Both {

        @McpResource(uri = "a://b", uriTemplate = "a://{c}")
        public String a(String c) {
            return "";
        }
    }


    public static class WrongVariable {

        @McpResource(uriTemplate = "a://{name}")
        public String a(String other) {
            return "";
        }
    }


    public static class NotString {

        @McpResource(uriTemplate = "a://{id}")
        public String a(int id) {
            return "";
        }
    }


    public static class Voided {

        @McpResource(uri = "a://b")
        public void a() {
        }
    }


    public static class BadTemplate {

        @McpResource(uriTemplate = "{x}")
        public String t(String x) {
            return x;
        }
    }


    public static class IntArgument {

        @McpPrompt
        public String p(int count) {
            return "";
        }
    }


    public static class BadReturn {

        @McpPrompt
        public int p() {
            return 1;
        }
    }
}
