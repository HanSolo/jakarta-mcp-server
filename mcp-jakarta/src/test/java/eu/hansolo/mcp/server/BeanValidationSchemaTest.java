package eu.hansolo.mcp.server;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import eu.hansolo.mcp.api.McpTool;
import eu.hansolo.mcp.api.McpToolHandler;
import eu.hansolo.mcp.tools.BeanSource;
import eu.hansolo.mcp.tools.ToolScanner;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Bean Validation constraints become schema keywords, and the argument validation enforces them. */
class BeanValidationSchemaTest {

    public record Guest(@NotBlank @Size(max = 20) String name, @Min(1) @Max(6) int seats, @Email Optional<String> email) {
    }


    public static class Tools {

        @McpTool
        public String book(@NotBlank @Size(max = 10) String duck, @Min(1) @Max(5) int times, @Positive BigDecimal price, @Pattern(regexp = "[A-Z]{3}") String code) {
            return duck;
        }

        @McpTool
        public String more(@NotEmpty List<@NotBlank String> names, @Size(min = 1, max = 2) Map<String, Integer> counts, @PositiveOrZero Optional<Integer> age,
                           @DecimalMin(value = "0.5", inclusive = false) double rate, @NotNull String plain, Guest guest) {
            return "ok";
        }

        @McpTool
        public String both(@NotBlank @Pattern(regexp = "[a-z]+") String word) {
            return word;
        }
    }


    private final Map<String, McpToolHandler> tools = ToolScanner.scan(Tools.class, BeanSource.of(new Tools())).stream().collect(Collectors.toMap(McpToolHandler::name, t -> t));


    private JsonObject property(String tool, String name) {
        return tools.get(tool).inputSchema().getJsonObject("properties").getJsonObject(name);
    }

    private Optional<String> check(String tool, JsonObject arguments) {
        return SchemaValidator.validate(tools.get(tool).inputSchema(), arguments);
    }

    private static JsonObject book(String duck, int times, double price, String code) {
        return Json.createObjectBuilder().add("duck", duck).add("times", times).add("price", price).add("code", code).build();
    }


    @Test
    void textConstraintsBecomeLengthAndPatternKeywords() {
        JsonObject duck = property("book", "duck");
        assertEquals(1, duck.getInt("minLength"));
        assertEquals(10, duck.getInt("maxLength"));
        assertEquals("\\S", duck.getString("pattern"));
    }

    @Test
    void aPatternIsAnchoredBecauseBeanValidationMatchesTheWholeText() {
        assertEquals("^(?:[A-Z]{3})$", property("book", "code").getString("pattern"));
        assertTrue(check("book", book("Donald", 1, 2.5, "ABCD")).isPresent());
        assertTrue(check("book", book("Donald", 1, 2.5, "xABC")).isPresent());
        assertFalse(check("book", book("Donald", 1, 2.5, "ABC")).isPresent());
    }

    @Test
    void numberConstraintsBecomeBounds() {
        assertEquals(1, property("book", "times").getInt("minimum"));
        assertEquals(5, property("book", "times").getInt("maximum"));
        assertEquals(0, property("book", "price").getInt("exclusiveMinimum"));
        assertTrue(check("book", book("Donald", 0, 2.5, "ABC")).orElse("").contains("times"));
        assertTrue(check("book", book("Donald", 6, 2.5, "ABC")).isPresent());
        assertTrue(check("book", book("Donald", 1, 0, "ABC")).orElse("").contains("price"));
    }

    @Test
    void aBlankTextAndATooLongTextAreRefusedWithTheArgumentName() {
        assertTrue(check("book", book("   ", 1, 2.5, "ABC")).orElse("").contains("duck"));
        assertTrue(check("book", book("", 1, 2.5, "ABC")).orElse("").contains("duck"));
        assertTrue(check("book", book("A very long duck name", 1, 2.5, "ABC")).orElse("").contains("duck"));
    }

    @Test
    void constraintsOnTypeArgumentsApplyToTheItems() {
        JsonObject names = property("more", "names");
        assertEquals(1, names.getInt("minItems"));
        assertEquals(1, names.getJsonObject("items").getInt("minLength"));
    }

    @Test
    void sizeOnAMapLimitsTheNumberOfEntries() {
        JsonObject counts = property("more", "counts");
        assertEquals(1, counts.getInt("minProperties"));
        assertEquals(2, counts.getInt("maxProperties"));
    }

    @Test
    void constraintsInsideAnOptionalApplyToTheValue() {
        assertEquals(0, property("more", "age").getInt("minimum"));
        assertEquals(new BigDecimal("0.5"), property("more", "rate").getJsonNumber("exclusiveMinimum").bigDecimalValue());
    }

    @Test
    void notNullAddsNothing() {
        assertEquals(Json.createObjectBuilder().add("type", "string").build(), property("more", "plain"));
    }

    @Test
    void constraintsOnRecordComponentsAreInTheSchemaOfTheRecord() {
        JsonObject guest = property("more", "guest").getJsonObject("properties");
        assertEquals(20, guest.getJsonObject("name").getInt("maxLength"));
        assertEquals(6, guest.getJsonObject("seats").getInt("maximum"));
        assertEquals("email", guest.getJsonObject("email").getString("format"));
    }

    @Test
    void twoPatternsAreCombinedWithAllOf() {
        JsonObject word = property("both", "word");
        assertFalse(word.containsKey("pattern"));
        assertEquals(2, word.getJsonArray("allOf").size());
        assertTrue(check("both", Json.createObjectBuilder().add("word", "ABC").build()).isPresent());
        assertFalse(check("both", Json.createObjectBuilder().add("word", "abc").build()).isPresent());
    }

    @Test
    void aSchemaWithoutConstraintsIsUnchanged() {
        assertEquals(List.of("type"), List.copyOf(property("more", "plain").keySet()));
    }
}
