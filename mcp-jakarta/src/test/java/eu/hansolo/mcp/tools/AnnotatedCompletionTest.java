package eu.hansolo.mcp.tools;

import jakarta.json.Json;
import eu.hansolo.mcp.api.Caller;
import eu.hansolo.mcp.api.CompletionCall;
import eu.hansolo.mcp.api.CompletionResult;
import eu.hansolo.mcp.api.McpComplete;
import eu.hansolo.mcp.api.McpCompletionHandler;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnnotatedCompletionTest {

    public static class Sample {

        @McpComplete(prompt = "p", argument = "style", scopes = {"s:read"})
        public List<String> styles(String typed) {
            return List.of(typed + "1", typed + "2");
        }

        @McpComplete(resourceTemplate = "x://{id}", argument = "id")
        public CompletionResult ids(CompletionCall call, Caller caller) {
            return CompletionResult.of(call.argument() + ":" + call.contextArguments().get("a") + ":" + caller.subject());
        }
    }


    private final List<McpCompletionHandler> handlers = CompletionScanner.scan(Sample.class, BeanSource.of(new Sample()));

    private CompletionCall call(McpCompletionHandler.Kind kind, String target, String argument, String value) {
        return new CompletionCall(kind, target, argument, value, Map.of("a", "b"), Json.createObjectBuilder().build(), null, new Caller("alice", Set.of(), Json.createObjectBuilder().build()));
    }

    @Test
    void methodsBecomeHandlersForTheirTargetAndArgument() throws Exception {
        McpCompletionHandler styles = handlers.stream().filter(h -> h.argument().equals("style")).findFirst().orElseThrow();
        assertEquals(McpCompletionHandler.Kind.PROMPT, styles.kind());
        assertEquals("p", styles.target());
        assertEquals(Set.of("s:read"), styles.requiredScopes());
        assertEquals(List.of("ab1", "ab2"), styles.complete(call(styles.kind(), "p", "style", "ab")).values());
        McpCompletionHandler ids = handlers.stream().filter(h -> h.argument().equals("id")).findFirst().orElseThrow();
        assertEquals(McpCompletionHandler.Kind.RESOURCE_TEMPLATE, ids.kind());
        assertEquals(List.of("id:b:alice"), ids.complete(call(ids.kind(), "x://{id}", "id", "")).values());
    }

    @Test
    void misdeclaredMethodsFailAtScanTime() {
        assertTrue(assertThrows(IllegalStateException.class, () -> CompletionScanner.scan(Neither.class, BeanSource.of(new Neither()))).getMessage().contains("exactly one of prompt and resourceTemplate"));
        assertTrue(assertThrows(IllegalStateException.class, () -> CompletionScanner.scan(TwoStrings.class, BeanSource.of(new TwoStrings()))).getMessage().contains("at most one String"));
        assertTrue(assertThrows(IllegalStateException.class, () -> CompletionScanner.scan(BadReturn.class, BeanSource.of(new BadReturn()))).getMessage().contains("unsupported completion return type"));
        assertTrue(assertThrows(IllegalStateException.class, () -> CompletionScanner.scan(BadParam.class, BeanSource.of(new BadParam()))).getMessage().contains("use String, CompletionCall or Caller"));
    }


    public static class Neither {

        @McpComplete(argument = "a")
        public List<String> a() {
            return List.of();
        }
    }


    public static class TwoStrings {

        @McpComplete(prompt = "p", argument = "a")
        public List<String> a(String x, String y) {
            return List.of();
        }
    }


    public static class BadReturn {

        @McpComplete(prompt = "p", argument = "a")
        public String a() {
            return "";
        }
    }


    public static class BadParam {

        @McpComplete(prompt = "p", argument = "a")
        public List<String> a(int x) {
            return List.of();
        }
    }
}
