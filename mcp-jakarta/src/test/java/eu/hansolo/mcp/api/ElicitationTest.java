package eu.hansolo.mcp.api;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Building elicitation requests and reading the answers. */
class ElicitationTest {

    private static JsonObject params(Elicitation.Request request) {
        JsonObject json = request.toJson();
        assertEquals("elicitation/create", json.getString("method"));
        return json.getJsonObject("params");
    }

    private static Elicitation.Answer accepted(String contentJson) {
        JsonObject responses = Json.createReader(new java.io.StringReader("{\"k\":{\"action\":\"accept\",\"content\":" + contentJson + "}}")).readObject();
        return Elicitation.answer(responses, "k").orElseThrow();
    }


    @Test
    void aFormIsAFlatObjectOfPrimitives() {
        Elicitation.Form form = Elicitation.form("Contact details")
                .string("name", p -> p.title("Full name").minLength(2).maxLength(40).required())
                .string("email", p -> p.format("email").required())
                .integer("age", p -> p.minimum(18).maximum(120))
                .number("score", p -> p.defaultValue(1.5))
                .bool("subscribe", p -> p.defaultValue(true));
        JsonObject params = params(form);
        assertEquals("form", params.getString("mode"));
        assertEquals("Contact details", params.getString("message"));
        JsonObject schema = params.getJsonObject("requestedSchema");
        assertEquals("object", schema.getString("type"));
        assertEquals("[\"name\",\"email\"]", schema.getJsonArray("required").toString());
        JsonObject props = schema.getJsonObject("properties");
        assertEquals("string", props.getJsonObject("name").getString("type"));
        assertEquals("Full name", props.getJsonObject("name").getString("title"));
        assertEquals(2, props.getJsonObject("name").getInt("minLength"));
        assertEquals("email", props.getJsonObject("email").getString("format"));
        assertEquals("integer", props.getJsonObject("age").getString("type"));
        assertEquals("boolean", props.getJsonObject("subscribe").getString("type"));
        assertEquals(true, props.getJsonObject("subscribe").getBoolean("default"));
    }

    @Test
    void aFormWithoutRequiredPropertiesHasNoRequiredField() {
        assertFalse(params(Elicitation.form("Anything else?").string("note")).getJsonObject("requestedSchema").containsKey("required"));
    }

    @Test
    void choicesWithAndWithoutTitles() {
        Map<String, String> titled = new LinkedHashMap<>();
        titled.put("#FF0000", "Red");
        titled.put("#00FF00", "Green");
        JsonObject props = params(Elicitation.form("Colors")
                .choice("plain", List.of("Red", "Green"), p -> p.defaultValue("Red"))
                .choice("titled", titled, p -> { })
                .multiChoice("many", List.of("a", "b", "c"), p -> p.minItems(1).maxItems(2).defaultValue(List.of("a")))
                .multiChoice("manyTitled", titled, p -> { })).getJsonObject("requestedSchema").getJsonObject("properties");
        assertEquals(2, props.getJsonObject("plain").getJsonArray("enum").size());
        JsonObject first = props.getJsonObject("titled").getJsonArray("oneOf").getJsonObject(0);
        assertEquals("#FF0000", first.getString("const"));
        assertEquals("Red", first.getString("title"));
        assertEquals("array", props.getJsonObject("many").getString("type"));
        assertEquals(2, props.getJsonObject("many").getInt("maxItems"));
        assertEquals(3, props.getJsonObject("many").getJsonObject("items").getJsonArray("enum").size());
        assertEquals("#00FF00", props.getJsonObject("manyTitled").getJsonObject("items").getJsonArray("anyOf").getJsonObject(1).getString("const"));
    }

    @Test
    void aFormWithoutPropertiesIsAPlainConfirmation() {
        Elicitation.Form form = Elicitation.form("Close ticket 3?");
        JsonObject params = params(form);
        assertEquals("form", params.getString("mode"));
        assertEquals("Close ticket 3?", params.getString("message"));
        JsonObject schema = params.getJsonObject("requestedSchema");
        assertEquals("object", schema.getString("type"));
        assertTrue(schema.getJsonObject("properties").isEmpty());
        assertFalse(schema.containsKey("required"));
        assertTrue(form.problems(accepted("{}")).isEmpty());
        assertEquals(List.of("unexpected property 'x'"), form.problems(accepted("{\"x\":1}")));
    }

    @Test
    void misuseFailsWhenTheFormIsBuilt() {
        assertThrows(IllegalArgumentException.class, () -> Elicitation.form(" "));
        assertThrows(IllegalArgumentException.class, () -> Elicitation.form("x").string("a").string("a"));
        assertThrows(IllegalArgumentException.class, () -> Elicitation.form("x").string("", p -> { }));
        assertThrows(IllegalStateException.class, () -> Elicitation.form("x").string("a", p -> p.minimum(1)));
        assertThrows(IllegalStateException.class, () -> Elicitation.form("x").integer("a", p -> p.minLength(1)));
        assertThrows(IllegalStateException.class, () -> Elicitation.form("x").bool("a", p -> p.defaultValue("yes")));
        assertThrows(IllegalArgumentException.class, () -> Elicitation.form("x").string("a", p -> p.format("phone")));
        assertThrows(IllegalArgumentException.class, () -> Elicitation.form("x").choice("a", List.of(), p -> { }));
        assertThrows(IllegalArgumentException.class, () -> Elicitation.form("x").choice("a", List.of("one"), p -> p.defaultValue("two")));
        assertThrows(IllegalArgumentException.class, () -> Elicitation.form("x").string("a", p -> p.minLength(5).defaultValue("abc")));
    }

    @Test
    void formModeRefusesWhatLooksLikeSensitiveData() {
        assertThrows(IllegalArgumentException.class, () -> Elicitation.form("x").string("password"));
        assertThrows(IllegalArgumentException.class, () -> Elicitation.form("x").string("a", p -> p.title("Your API key")));
        assertThrows(IllegalArgumentException.class, () -> Elicitation.form("x").string("a", p -> p.description("paste the access token here")));
        assertThrows(IllegalArgumentException.class, () -> Elicitation.form("Please enter your password"));
        assertThrows(IllegalArgumentException.class, () -> Elicitation.form("x").string("credit_card_number"));
        Elicitation.form("Which environment?").string("pinned").string("tokenCount");
    }

    @Test
    void aUrlRequestNeedsAnAbsoluteHttpUrlWithoutCredentials() {
        JsonObject params = params(Elicitation.url("Enter your API key on our secure page", "https://mcp.example.com/ui/set_api_key?c=1"));
        assertEquals("url", params.getString("mode"));
        assertEquals("https://mcp.example.com/ui/set_api_key?c=1", params.getString("url"));
        assertFalse(params.containsKey("requestedSchema"));
        assertTrue(params(Elicitation.url("m", "http://localhost:8080/x")).getString("url").startsWith("http://localhost"));
        for (String bad : new String[] { "javascript:alert(1)", "file:///etc/passwd", "/relative/path", "https://user:pw@example.com/", "https:///nohost", "not a url", "", null }) {
            assertThrows(IllegalArgumentException.class, () -> Elicitation.url("m", bad));
        }
        assertThrows(IllegalArgumentException.class, () -> Elicitation.url("", "https://example.com/"));
    }

    @Test
    void inputRequestsHoldKeysInOrderAndRefuseDuplicates() {
        InputRequests requests = InputRequests.of("first", Elicitation.form("a").string("x")).add("second", Elicitation.url("b", "https://example.com/"));
        assertEquals(List.of("first", "second"), List.copyOf(requests.keys()));
        JsonObject json = requests.toJson();
        assertEquals("form", json.getJsonObject("first").getJsonObject("params").getString("mode"));
        assertEquals("url", json.getJsonObject("second").getJsonObject("params").getString("mode"));
        assertThrows(IllegalArgumentException.class, () -> requests.add("first", Elicitation.url("c", "https://example.com/")));
        assertThrows(IllegalArgumentException.class, () -> requests.add(" ", Elicitation.url("c", "https://example.com/")));
        assertThrows(IllegalArgumentException.class, () -> requests.add("third", null));
    }

    @Test
    void askBuildsTheInputRequiredResult() {
        ToolResult result = ToolResult.ask(InputRequests.of("k", Elicitation.form("m").string("x")), "state");
        ToolResult.InputRequired required = (ToolResult.InputRequired) result;
        assertEquals("state", required.requestState());
        assertTrue(required.inputRequests().containsKey("k"));
    }

    @Test
    void anAnswerHasAnActionAndTypedContent() {
        Elicitation.Answer answer = accepted("{\"name\":\"octocat\",\"age\":30,\"score\":1.5,\"ok\":true,\"tags\":[\"a\",\"b\",3]}");
        assertTrue(answer.accepted());
        assertEquals("octocat", answer.string("name").orElseThrow());
        assertEquals(30L, (long) answer.integer("age").orElseThrow());
        assertEquals(1.5, (double) answer.number("score").orElseThrow());
        assertEquals(true, answer.bool("ok").orElseThrow());
        assertEquals(List.of("a", "b"), answer.strings("tags"));
        assertTrue(answer.string("age").isEmpty());
        assertTrue(answer.integer("score").isEmpty());
        assertTrue(answer.bool("name").isEmpty());
        assertTrue(answer.string("missing").isEmpty());
        assertTrue(answer.strings("missing").isEmpty());
    }

    @Test
    void declineCancelAndTheRestAreNotAccepted() {
        JsonObject responses = Json.createReader(new java.io.StringReader("{\"a\":{\"action\":\"decline\"},\"b\":{\"action\":\"cancel\"},\"c\":{\"action\":\"surprise\"},\"d\":{\"action\":\"decline\",\"content\":{\"x\":1}},\"e\":{\"content\":{}},\"f\":\"accept\",\"g\":{\"action\":\"accept\"}}")).readObject();
        assertTrue(Elicitation.answer(responses, "a").orElseThrow().declined());
        assertTrue(Elicitation.answer(responses, "b").orElseThrow().cancelled());
        assertTrue(Elicitation.answer(responses, "c").orElseThrow().cancelled());
        assertTrue(Elicitation.answer(responses, "d").orElseThrow().content().isEmpty());
        assertTrue(Elicitation.answer(responses, "e").isEmpty());
        assertTrue(Elicitation.answer(responses, "f").isEmpty());
        assertTrue(Elicitation.answer(responses, "missing").isEmpty());
        assertTrue(Elicitation.answer(null, "a").isEmpty());
        Elicitation.Answer urlAnswer = Elicitation.answer(responses, "g").orElseThrow();
        assertTrue(urlAnswer.accepted() && urlAnswer.content().isEmpty());
    }

    @Test
    void theClientsDeclaredModes() {
        JsonObject both  = Json.createObjectBuilder().add("elicitation", Json.createObjectBuilder().add("form", Json.createObjectBuilder()).add("url", Json.createObjectBuilder())).build();
        JsonObject empty = Json.createObjectBuilder().add("elicitation", Json.createObjectBuilder()).build();
        JsonObject url   = Json.createObjectBuilder().add("elicitation", Json.createObjectBuilder().add("url", Json.createObjectBuilder())).build();
        assertTrue(Elicitation.supports(both, Elicitation.Mode.FORM) && Elicitation.supports(both, Elicitation.Mode.URL));
        assertTrue(Elicitation.supports(empty, Elicitation.Mode.FORM));
        assertFalse(Elicitation.supports(empty, Elicitation.Mode.URL));
        assertTrue(Elicitation.supports(url, Elicitation.Mode.URL));
        assertFalse(Elicitation.supports(url, Elicitation.Mode.FORM));
        assertFalse(Elicitation.supports(Json.createObjectBuilder().build(), Elicitation.Mode.FORM));
        assertFalse(Elicitation.supports(null, Elicitation.Mode.FORM));
        ToolCall call = new ToolCall("t", Json.createObjectBuilder().build(), empty, null, null, null, Caller.ANONYMOUS);
        assertTrue(call.clientSupportsElicitation(Elicitation.Mode.FORM));
        assertFalse(call.clientSupportsElicitation(Elicitation.Mode.URL));
    }

    @Test
    void theAnswerIsCheckedAgainstTheForm() {
        Elicitation.Form form = Elicitation.form("Details")
                .string("name", p -> p.minLength(2).maxLength(5).required())
                .string("email", p -> p.format("email"))
                .string("when", p -> p.format("date"))
                .integer("age", p -> p.minimum(18))
                .bool("ok", p -> { })
                .choice("color", List.of("Red", "Green"), p -> { })
                .multiChoice("tags", List.of("a", "b"), p -> p.minItems(1).maxItems(2));
        assertEquals(List.of(), form.problems(accepted("{\"name\":\"abc\",\"email\":\"a@b.co\",\"when\":\"2026-11-09\",\"age\":20,\"ok\":false,\"color\":\"Red\",\"tags\":[\"a\"]}")));
        assertEquals(List.of("'name' is required"), form.problems(accepted("{}")));
        assertEquals(1, form.problems(accepted("{\"name\":\"a\"}")).size());
        assertEquals(1, form.problems(accepted("{\"name\":\"abcdefg\"}")).size());
        assertEquals(1, form.problems(accepted("{\"name\":\"abc\",\"email\":\"nope\"}")).size());
        assertEquals(1, form.problems(accepted("{\"name\":\"abc\",\"when\":\"09.11.2026\"}")).size());
        assertEquals(1, form.problems(accepted("{\"name\":\"abc\",\"age\":17}")).size());
        assertEquals(1, form.problems(accepted("{\"name\":\"abc\",\"age\":20.5}")).size());
        assertEquals(1, form.problems(accepted("{\"name\":\"abc\",\"ok\":\"yes\"}")).size());
        assertEquals(1, form.problems(accepted("{\"name\":\"abc\",\"color\":\"Blue\"}")).size());
        assertEquals(1, form.problems(accepted("{\"name\":\"abc\",\"tags\":[]}")).size());
        assertEquals(1, form.problems(accepted("{\"name\":\"abc\",\"tags\":[\"a\",\"b\",\"a\"]}")).size());
        assertEquals(1, form.problems(accepted("{\"name\":\"abc\",\"tags\":[\"z\"]}")).size());
        assertEquals(1, form.problems(accepted("{\"name\":\"abc\",\"extra\":1}")).size());
    }
}
