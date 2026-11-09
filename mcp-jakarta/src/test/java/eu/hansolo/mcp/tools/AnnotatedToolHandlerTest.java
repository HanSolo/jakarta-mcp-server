package eu.hansolo.mcp.tools;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import eu.hansolo.mcp.api.Caller;
import eu.hansolo.mcp.api.McpParam;
import eu.hansolo.mcp.api.McpTool;
import eu.hansolo.mcp.api.McpToolHandler;
import eu.hansolo.mcp.api.ToolCall;
import eu.hansolo.mcp.api.ToolExecutionException;
import eu.hansolo.mcp.api.ToolResult;
import eu.hansolo.mcp.protocol.McpProtocolException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnnotatedToolHandlerTest {

    public record Sum(@McpParam(description = "the total") double total, Optional<String> note) {
    }


    public static class Decorated {

        @McpTool(icons = {"https://example.com/a.png", "data:image/svg+xml;base64,AAAA"}, meta = "{\"com.example/team\":\"billing\"}")
        public String shown() {
            return "x";
        }
    }


    public static class BadIcon {

        @McpTool(icons = {"javascript:alert(1)"})
        public String shown() {
            return "x";
        }
    }


    public static class BadMetaText {

        @McpTool(meta = "[1]")
        public String shown() {
            return "x";
        }
    }


    public static class Sample {

        @McpTool(description = "Adds", readOnlyHint = McpTool.Hint.TRUE, idempotentHint = McpTool.Hint.TRUE, destructiveHint = McpTool.Hint.FALSE)
        public Sum add(@McpParam(description = "first") double a, double b, Optional<String> note) {
            return new Sum(a + b, note);
        }

        @McpTool(name = "shout", title = "Shout it", scopes = {"text:write"})
        public String loud(String text) {
            return text.toUpperCase();
        }

        @McpTool
        public void nothing() {
        }

        @McpTool
        public String fails(String reason) {
            throw new ToolExecutionException("cannot: " + reason);
        }

        @McpTool
        public String crashes() {
            throw new IllegalStateException("secret internals");
        }

        @McpTool
        public String who(Caller caller, ToolCall call) {
            return caller.subject() + "/" + call.name();
        }

        @McpTool
        public ToolResult raw() {
            return ToolResult.inputRequired(Json.createObjectBuilder().add("k", Json.createObjectBuilder()).build(), null);
        }

        @McpTool
        public String optionalObject(@McpParam(required = false) String maybe) {
            return String.valueOf(maybe);
        }

        @McpTool
        public Map<String, Integer> counts() {
            return Map.of("a", 1);
        }

        @McpTool
        public List<String> names() {
            return List.of("x", "y");
        }

        @McpTool
        public String renamed(@McpParam(name = "other-name") String value) {
            return value;
        }

        public String notATool() {
            return "no";
        }
    }


    public static class NonPublic {

        @McpTool
        String hidden() {
            return "";
        }
    }


    public static class Unsupported {

        @McpTool
        public String bad(Thread thread) {
            return "";
        }
    }


    public static class Duplicate {

        @McpTool
        public String twice(String a, @McpParam(name = "a") String b) {
            return "";
        }
    }


    public static class PrimitiveOptional {

        @McpTool
        public String bad(@McpParam(required = false) int n) {
            return "";
        }
    }

    private final Map<String, McpToolHandler> tools = ToolScanner.scan(Sample.class, BeanSource.of(new Sample())).stream().collect(Collectors.toMap(McpToolHandler::name, t -> t));

    private ToolResult.Complete call(String tool, JsonObject arguments) throws Exception {
        ToolCall call = new ToolCall(tool, arguments, JsonObject.EMPTY_JSON_OBJECT, null, null, null, new Caller("alice", Set.of(), JsonObject.EMPTY_JSON_OBJECT));
        return (ToolResult.Complete) tools.get(tool).call(call);
    }

    private static String text(ToolResult.Complete result) {
        return result.content().getJsonObject(0).getString("text");
    }

    @Test
    void onlyAnnotatedMethodsBecomeToolsWithDefaultNames() {
        assertEquals(Set.of("add", "shout", "nothing", "fails", "crashes", "who", "raw", "optionalObject", "counts", "names", "renamed"), tools.keySet());
    }

    @Test
    void metadataComesFromTheAnnotation() {
        McpToolHandler shout = tools.get("shout");
        assertEquals("Shout it", shout.title());
        assertEquals(Set.of("text:write"), shout.requiredScopes());
        assertNull(shout.description());
        JsonObject add = tools.get("add").annotations();
        assertTrue(add.getBoolean("readOnlyHint"));
        assertTrue(add.getBoolean("idempotentHint"));
        assertFalse(add.getBoolean("destructiveHint"));
        assertFalse(add.containsKey("openWorldHint"));
        assertNull(tools.get("nothing").annotations());
    }

    @Test
    void inputSchemaListsArgumentsWithRequiredAndDescriptions() {
        JsonObject schema = tools.get("add").inputSchema();
        assertEquals("number", schema.getJsonObject("properties").getJsonObject("a").getString("type"));
        assertEquals("first", schema.getJsonObject("properties").getJsonObject("a").getString("description"));
        assertEquals(List.of("a", "b"), schema.getJsonArray("required").stream().map(v -> ((jakarta.json.JsonString) v).getString()).toList());
        assertFalse(schema.getBoolean("additionalProperties"));
        assertEquals("string", schema.getJsonObject("properties").getJsonObject("note").getString("type"));
    }

    @Test
    void injectedParametersStayOutOfTheSchema() {
        JsonObject schema = tools.get("who").inputSchema();
        assertTrue(schema.getJsonObject("properties").isEmpty());
        assertFalse(schema.containsKey("required"));
    }

    @Test
    void explicitParameterNamesOverrideJavaNames() throws Exception {
        assertTrue(tools.get("renamed").inputSchema().getJsonObject("properties").containsKey("other-name"));
        assertEquals("v", text(call("renamed", Json.createObjectBuilder().add("other-name", "v").build())));
    }

    @Test
    void recordResultsAreStructuredAndAdvertiseAnOutputSchema() throws Exception {
        ToolResult.Complete result = call("add", Json.createObjectBuilder().add("a", 1.5).add("b", 2).build());
        assertEquals(3.5, ((JsonObject) result.structuredContent()).getJsonNumber("total").doubleValue());
        assertFalse(((JsonObject) result.structuredContent()).containsKey("note"));
        assertFalse(result.isError());
        JsonObject output = tools.get("add").outputSchema();
        assertEquals("the total", output.getJsonObject("properties").getJsonObject("total").getString("description"));
        assertNull(tools.get("shout").outputSchema());
    }

    @Test
    void presentOptionalsAppearInTheResult() throws Exception {
        ToolResult.Complete result = call("add", Json.createObjectBuilder().add("a", 1).add("b", 2).add("note", "hi").build());
        assertEquals("hi", ((JsonObject) result.structuredContent()).getString("note"));
    }

    @Test
    void stringVoidMapAndListResults() throws Exception {
        assertEquals("HELLO", text(call("shout", Json.createObjectBuilder().add("text", "hello").build())));
        assertEquals(0, call("nothing", JsonObject.EMPTY_JSON_OBJECT).content().size());
        assertEquals(1, ((JsonObject) call("counts", JsonObject.EMPTY_JSON_OBJECT).structuredContent()).getInt("a"));
        ToolResult.Complete names = call("names", JsonObject.EMPTY_JSON_OBJECT);
        assertNull(names.structuredContent());
        assertEquals("[\"x\",\"y\"]", text(names));
    }

    @Test
    void toolExecutionExceptionBecomesAnErrorResultWithItsMessage() throws Exception {
        ToolResult.Complete result = call("fails", Json.createObjectBuilder().add("reason", "busy").build());
        assertTrue(result.isError());
        assertEquals("cannot: busy", text(result));
    }

    @Test
    void otherExceptionsPropagateSoTheServerCanHideTheirDetails() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> call("crashes", JsonObject.EMPTY_JSON_OBJECT));
        assertEquals("secret internals", e.getMessage());
    }

    @Test
    void callerAndCallAreInjected() throws Exception {
        assertEquals("alice/who", text(call("who", JsonObject.EMPTY_JSON_OBJECT)));
    }

    @Test
    void toolResultsPassThroughUntouched() throws Exception {
        ToolResult result = tools.get("raw").call(new ToolCall("raw", JsonObject.EMPTY_JSON_OBJECT, JsonObject.EMPTY_JSON_OBJECT, null, null, null, Caller.ANONYMOUS));
        assertTrue(result instanceof ToolResult.InputRequired);
    }

    @Test
    void optionalObjectParametersMayBeOmitted() throws Exception {
        assertEquals("null", text(call("optionalObject", JsonObject.EMPTY_JSON_OBJECT)));
        assertEquals("x", text(call("optionalObject", Json.createObjectBuilder().add("maybe", "x").build())));
    }

    @Test
    void missingRequiredAndWrongTypesAreInvalidArguments() {
        McpProtocolException missing = assertThrows(McpProtocolException.class, () -> call("shout", JsonObject.EMPTY_JSON_OBJECT));
        assertTrue(missing.getMessage().contains("'text' is required"));
        McpProtocolException wrong = assertThrows(McpProtocolException.class, () -> call("shout", Json.createObjectBuilder().add("text", 5).build()));
        assertTrue(wrong.getMessage().contains("must be a string"));
    }

    @Test
    void declarationMistakesFailAtScanTimeWithTheMethodName() {
        assertTrue(assertThrows(IllegalStateException.class, () -> ToolScanner.scan(NonPublic.class, BeanSource.of(new NonPublic()))).getMessage().contains("NonPublic.hidden"));
        assertTrue(assertThrows(IllegalStateException.class, () -> ToolScanner.scan(Unsupported.class, BeanSource.of(new Unsupported()))).getMessage().contains("parameter 'thread'"));
        assertTrue(assertThrows(IllegalStateException.class, () -> ToolScanner.scan(Duplicate.class, BeanSource.of(new Duplicate()))).getMessage().contains("duplicate parameter name 'a'"));
        assertTrue(assertThrows(IllegalStateException.class, () -> ToolScanner.scan(PrimitiveOptional.class, BeanSource.of(new PrimitiveOptional()))).getMessage().contains("primitive"));
    }

    @Test
    void iconsAndMetaComeFromTheAnnotation() {
        McpToolHandler tool = ToolScanner.scan(Decorated.class, BeanSource.of(new Decorated())).get(0);
        assertEquals(2, tool.icons().size());
        assertEquals("https://example.com/a.png", tool.icons().get(0).src());
        assertEquals("billing", tool.meta().getString("com.example/team"));
        McpToolHandler plain = tools.get("nothing");
        assertTrue(plain.icons().isEmpty());
        assertNull(plain.meta());
    }

    @Test
    void anUnsafeIconOrBrokenMetaFailsAtStartupWithTheMethodName() {
        assertTrue(assertThrows(IllegalStateException.class, () -> ToolScanner.scan(BadIcon.class, BeanSource.of(new BadIcon()))).getMessage().contains("BadIcon.shown"));
        assertTrue(assertThrows(IllegalStateException.class, () -> ToolScanner.scan(BadMetaText.class, BeanSource.of(new BadMetaText()))).getMessage().contains("meta must be a JSON object"));
    }

    @Test
    void hasToolsIsAccurate() {
        assertTrue(ToolScanner.hasTools(Sample.class));
        assertFalse(ToolScanner.hasTools(String.class));
    }
}
