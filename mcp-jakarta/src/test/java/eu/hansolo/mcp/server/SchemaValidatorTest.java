package eu.hansolo.mcp.server;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaValidatorTest {

    private static final JsonObject SCHEMA = Json.createObjectBuilder().add("type", "object").add("properties", Json.createObjectBuilder().add("s", Json.createObjectBuilder().add("type", "string")).add("i", Json.createObjectBuilder().add("type", "integer")).add("n", Json.createObjectBuilder().add("type", Json.createArrayBuilder().add("number").add("null")))).add("required", Json.createArrayBuilder().add("s")).add("additionalProperties", false).build();

    @Test
    void acceptsValidArguments() {
        assertTrue(SchemaValidator.validate(SCHEMA, Json.createObjectBuilder().add("s", "x").add("i", 3).add("n", 1.5).build()).isEmpty());
    }

    @Test
    void reportsMissingRequiredProperty() {
        assertEquals("Missing required property 's'", SchemaValidator.validate(SCHEMA, Json.createObjectBuilder().build()).orElseThrow());
    }

    @Test
    void reportsWrongTypes() {
        assertTrue(SchemaValidator.validate(SCHEMA, Json.createObjectBuilder().add("s", 1).build()).orElseThrow().contains("'s'"));
        assertTrue(SchemaValidator.validate(SCHEMA, Json.createObjectBuilder().add("s", "x").add("i", 1.5).build()).orElseThrow().contains("'i'"));
    }

    @Test
    void integerAcceptsWholeNumbersWrittenWithADecimalPoint() {
        assertTrue(SchemaValidator.validate(SCHEMA, Json.createObjectBuilder().add("s", "x").add("i", 2.0).build()).isEmpty());
    }

    @Test
    void typeMayBeAnArray() {
        assertTrue(SchemaValidator.validate(SCHEMA, Json.createObjectBuilder().add("s", "x").addNull("n").build()).isEmpty());
    }

    @Test
    void rejectsUnexpectedPropertiesWhenAdditionalPropertiesIsFalse() {
        assertEquals("Unexpected property 'z'", SchemaValidator.validate(SCHEMA, Json.createObjectBuilder().add("s", "x").add("z", 1).build()).orElseThrow());
    }

    // ---- the keywords ----------------------------------------------------------------------------------------------

    @Test
    void numbersHaveRanges() {
        JsonObject schema = schema("{\"type\":\"object\",\"properties\":{\"n\":{\"type\":\"number\",\"minimum\":1,\"maximum\":10,\"multipleOf\":0.5},\"x\":{\"exclusiveMinimum\":0,\"exclusiveMaximum\":1}}}");
        assertValid(schema, "{\"n\":1}");
        assertValid(schema, "{\"n\":10,\"x\":0.5}");
        assertEquals("Property 'n' must be at least 1", problem(schema, "{\"n\":0.5}"));
        assertEquals("Property 'n' must be at most 10", problem(schema, "{\"n\":10.5}"));
        assertEquals("Property 'n' must be a multiple of 0.5", problem(schema, "{\"n\":2.3}"));
        assertEquals("Property 'x' must be greater than 0", problem(schema, "{\"x\":0}"));
        assertEquals("Property 'x' must be less than 1", problem(schema, "{\"x\":1}"));
    }

    @Test
    void stringsHaveLengthsAndPatterns() {
        JsonObject schema = schema("{\"properties\":{\"s\":{\"type\":\"string\",\"minLength\":2,\"maxLength\":4,\"pattern\":\"^[a-z]+$\"}}}");
        assertValid(schema, "{\"s\":\"abc\"}");
        assertEquals("Property 's' must be at least 2 characters long", problem(schema, "{\"s\":\"a\"}"));
        assertEquals("Property 's' must be at most 4 characters long", problem(schema, "{\"s\":\"abcde\"}"));
        assertEquals("Property 's' must match the pattern ^[a-z]+$", problem(schema, "{\"s\":\"ab1\"}"));
        assertValid(schema("{\"properties\":{\"s\":{\"pattern\":\"b\"}}}"), "{\"s\":\"abc\"}");   // not anchored
        assertValid(schema("{\"properties\":{\"s\":{\"maxLength\":1}}}"), "{\"s\":\"\ud83d\ude00\"}");   // one code point
        assertValid(schema("{\"properties\":{\"s\":{\"pattern\":\"([\"}}}"), "{\"s\":\"x\"}");   // does not compile: ignored
    }

    @Test
    void enumAndConstCompareByValue() {
        JsonObject schema = schema("{\"properties\":{\"c\":{\"enum\":[\"red\",2,{\"a\":[1]},null]},\"k\":{\"const\":\"x\"}}}");
        assertValid(schema, "{\"c\":\"red\",\"k\":\"x\"}");
        assertValid(schema, "{\"c\":2.0}");
        assertValid(schema, "{\"c\":{\"a\":[1.0]}}");
        assertValid(schema, "{\"c\":null}");
        assertEquals("Property 'c' must be one of [\"red\",2,{\"a\":[1]},null]", problem(schema, "{\"c\":\"blue\"}"));
        assertEquals("Property 'k' must equal \"x\"", problem(schema, "{\"k\":\"y\"}"));
    }

    @Test
    void nestedObjectsAreCheckedWithTheirPath() {
        JsonObject schema = schema("{\"properties\":{\"a\":{\"type\":\"object\",\"required\":[\"city\"],\"properties\":{\"city\":{\"type\":\"string\"},\"zip\":{\"type\":\"integer\"}},\"additionalProperties\":false}}}");
        assertValid(schema, "{\"a\":{\"city\":\"Berlin\",\"zip\":10115}}");
        assertEquals("Missing required property 'a.city'", problem(schema, "{\"a\":{}}"));
        assertEquals("Property 'a.zip' must be of type integer", problem(schema, "{\"a\":{\"city\":\"x\",\"zip\":\"1\"}}"));
        assertEquals("Unexpected property 'a.street'", problem(schema, "{\"a\":{\"city\":\"x\",\"street\":\"y\"}}"));
        assertEquals("Property 'a' must be of type object", problem(schema, "{\"a\":[]}"));
    }

    @Test
    void arraysHaveItemsLimitsAndUniqueness() {
        JsonObject schema = schema("{\"properties\":{\"l\":{\"type\":\"array\",\"items\":{\"type\":\"integer\",\"minimum\":0},\"minItems\":1,\"maxItems\":3,\"uniqueItems\":true}}}");
        assertValid(schema, "{\"l\":[1,2,3]}");
        assertEquals("Property 'l' must have at least 1 items", problem(schema, "{\"l\":[]}"));
        assertEquals("Property 'l' must have at most 3 items", problem(schema, "{\"l\":[1,2,3,4]}"));
        assertEquals("Property 'l[1]' must be at least 0", problem(schema, "{\"l\":[1,-1]}"));
        assertEquals("Property 'l[0]' must be of type integer", problem(schema, "{\"l\":[\"a\"]}"));
        assertEquals("Property 'l' must not contain the same item twice", problem(schema, "{\"l\":[1,1.0]}"));
    }

    @Test
    void prefixItemsAndContains() {
        JsonObject tuple = schema("{\"properties\":{\"t\":{\"prefixItems\":[{\"type\":\"string\"},{\"type\":\"integer\"}],\"items\":{\"type\":\"boolean\"}}}}");
        assertValid(tuple, "{\"t\":[\"a\",1,true,false]}");
        assertEquals("Property 't[1]' must be of type integer", problem(tuple, "{\"t\":[\"a\",\"b\"]}"));
        assertEquals("Property 't[2]' must be of type boolean", problem(tuple, "{\"t\":[\"a\",1,5]}"));
        JsonObject contains = schema("{\"properties\":{\"c\":{\"contains\":{\"const\":7},\"minContains\":2,\"maxContains\":3}}}");
        assertValid(contains, "{\"c\":[7,1,7]}");
        assertEquals("Property 'c' must contain at least 2 matching items", problem(contains, "{\"c\":[7]}"));
        assertEquals("Property 'c' must contain at most 3 matching items", problem(contains, "{\"c\":[7,7,7,7]}"));
    }

    @Test
    void additionalPropertiesPatternPropertiesAndPropertyNames() {
        JsonObject typed = schema("{\"properties\":{\"a\":{}},\"additionalProperties\":{\"type\":\"integer\"}}");
        assertValid(typed, "{\"a\":\"anything\",\"b\":1}");
        assertEquals("Property 'b' must be of type integer", problem(typed, "{\"b\":\"x\"}"));
        JsonObject patterned = schema("{\"patternProperties\":{\"^x-\":{\"type\":\"string\"}},\"additionalProperties\":false}");
        assertValid(patterned, "{\"x-a\":\"v\"}");
        assertEquals("Property 'x-a' must be of type string", problem(patterned, "{\"x-a\":1}"));
        assertEquals("Unexpected property 'y'", problem(patterned, "{\"y\":\"v\"}"));
        JsonObject names = schema("{\"propertyNames\":{\"maxLength\":3}}");
        assertValid(names, "{\"abc\":1}");
        assertEquals("Property name 'abcd' is not allowed", problem(names, "{\"abcd\":1}"));
    }

    @Test
    void sizeAndDependenciesOfObjects() {
        JsonObject schema = schema("{\"minProperties\":1,\"maxProperties\":2,\"dependentRequired\":{\"card\":[\"cvv\"]}}");
        assertValid(schema, "{\"a\":1}");
        assertEquals("Arguments must have at least 1 properties", problem(schema, "{}"));
        assertEquals("Arguments must have at most 2 properties", problem(schema, "{\"a\":1,\"b\":2,\"c\":3}"));
        assertEquals("Missing required property 'cvv' (needed when 'card' is present)", problem(schema, "{\"card\":\"1\"}"));
        assertValid(schema, "{\"card\":\"1\",\"cvv\":\"2\"}");
    }

    @Test
    void combinators() {
        JsonObject schema = schema("{\"properties\":{\"v\":{\"anyOf\":[{\"type\":\"string\"},{\"type\":\"integer\"}]},\"o\":{\"oneOf\":[{\"type\":\"integer\"},{\"minimum\":5}]},\"n\":{\"not\":{\"type\":\"null\"}},\"a\":{\"allOf\":[{\"minLength\":2},{\"maxLength\":3}]}}}");
        assertValid(schema, "{\"v\":\"x\",\"o\":1,\"n\":0,\"a\":\"ab\"}");
        assertEquals("Property 'v' does not match any of the allowed shapes (anyOf)", problem(schema, "{\"v\":true}"));
        assertEquals("Property 'o' matches more than one of the shapes of oneOf", problem(schema, "{\"o\":7}"));
        assertEquals("Property 'o' does not match any of the allowed shapes (oneOf)", problem(schema, "{\"o\":2.5}"));
        assertEquals("Property 'n' matches a shape that is not allowed", problem(schema, "{\"n\":null}"));
        assertEquals("Property 'a' must be at most 3 characters long", problem(schema, "{\"a\":\"abcd\"}"));
    }

    @Test
    void ifThenElse() {
        JsonObject schema = schema("{\"if\":{\"properties\":{\"kind\":{\"const\":\"phone\"}},\"required\":[\"kind\"]},\"then\":{\"required\":[\"phone\"]},\"else\":{\"required\":[\"email\"]}}");
        assertValid(schema, "{\"kind\":\"phone\",\"phone\":\"1\"}");
        assertValid(schema, "{\"kind\":\"email\",\"email\":\"a@b\"}");
        assertEquals("Missing required property 'phone'", problem(schema, "{\"kind\":\"phone\"}"));
        assertEquals("Missing required property 'email'", problem(schema, "{\"kind\":\"email\"}"));
    }

    @Test
    void localReferencesAndAnchors() {
        JsonObject schema = schema("{\"$defs\":{\"address\":{\"$anchor\":\"addr\",\"type\":\"object\",\"required\":[\"city\"]},\"pct\":{\"type\":\"integer\",\"maximum\":100}},\"properties\":{\"home\":{\"$ref\":\"#/$defs/address\"},\"work\":{\"$ref\":\"#addr\"},\"p\":{\"$ref\":\"#/$defs/pct\"},\"me\":{\"$ref\":\"#\"},\"far\":{\"$ref\":\"https://example.com/other.json\"},\"gone\":{\"$ref\":\"#/$defs/missing\"}}}");
        assertValid(schema, "{\"home\":{\"city\":\"a\"},\"work\":{\"city\":\"b\"},\"p\":100,\"far\":1,\"gone\":2}");
        assertEquals("Missing required property 'home.city'", problem(schema, "{\"home\":{}}"));
        assertEquals("Missing required property 'work.city'", problem(schema, "{\"work\":{}}"));
        assertEquals("Property 'p' must be at most 100", problem(schema, "{\"p\":101}"));
        assertEquals("Property 'me.p' must be at most 100", problem(schema, "{\"me\":{\"p\":101}}"));
    }

    @Test
    void aReferenceThatLoopsEndsInAnError() {
        JsonObject loop = schema("{\"$defs\":{\"a\":{\"$ref\":\"#/$defs/b\"},\"b\":{\"$ref\":\"#/$defs/a\"}},\"properties\":{\"x\":{\"$ref\":\"#/$defs/a\"}}}");
        assertTrue(problem(loop, "{\"x\":1}").contains("nested too deeply"));
    }

    @Test
    void booleanSchemas() {
        JsonObject schema = schema("{\"properties\":{\"yes\":true,\"no\":false}}");
        assertValid(schema, "{\"yes\":1}");
        assertEquals("Property 'no' is not allowed", problem(schema, "{\"no\":1}"));
    }

    @Test
    void theJsonSchema2020FixtureSchema() {
        JsonObject schema = schema("{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"type\":\"object\",\"$defs\":{\"address\":{\"$anchor\":\"addressDef\",\"type\":\"object\",\"properties\":{\"street\":{\"type\":\"string\"}},\"additionalProperties\":false}},\"properties\":{\"name\":{\"type\":\"string\"},\"address\":{\"$ref\":\"#/$defs/address\"},\"contactMethod\":{\"type\":\"string\",\"enum\":[\"phone\",\"email\"]},\"phone\":{\"type\":\"string\"},\"email\":{\"type\":\"string\"}},\"allOf\":[{\"anyOf\":[{\"required\":[\"phone\"]},{\"required\":[\"email\"]}]}],\"if\":{\"properties\":{\"contactMethod\":{\"const\":\"phone\"}},\"required\":[\"contactMethod\"]},\"then\":{\"required\":[\"phone\"]},\"else\":{\"required\":[\"email\"]},\"additionalProperties\":false}");
        assertValid(schema, "{\"name\":\"A\",\"contactMethod\":\"email\",\"email\":\"a@b\",\"address\":{\"street\":\"s\"}}");
        assertValid(schema, "{\"contactMethod\":\"phone\",\"phone\":\"1\"}");
        assertTrue(problem(schema, "{\"contactMethod\":\"phone\",\"email\":\"a@b\"}").contains("phone"));
        assertTrue(problem(schema, "{\"name\":\"A\"}").contains("allowed shapes"));
        assertTrue(problem(schema, "{\"email\":\"a@b\",\"address\":{\"zip\":1}}").contains("address.zip"));
        assertTrue(problem(schema, "{\"email\":\"a@b\",\"contactMethod\":\"fax\"}").contains("contactMethod"));
    }

    @Test
    void messagesNeverEchoTheValuesTheClientSentAndShortenLongNames() {
        JsonObject schema = schema("{\"properties\":{\"s\":{\"type\":\"integer\"}}}");
        assertTrue(!problem(schema, "{\"s\":\"secret-value\"}").contains("secret-value"));
        String longName = "n".repeat(500);
        String message = problem(schema("{\"additionalProperties\":false}"), "{\"" + longName + "\":1}");
        assertTrue(message.length() < 120, message);
    }

    @Test
    void anEmptySchemaAcceptsAnything() {
        assertValid(schema("{}"), "{\"anything\":[1,{\"x\":null}]}");
    }

    private static JsonObject schema(String json) {
        return Json.createReader(new StringReader(json)).readObject();
    }

    private static void assertValid(JsonObject schema, String arguments) {
        assertTrue(SchemaValidator.validate(schema, schema(arguments)).isEmpty(), arguments + " should be valid but: " + SchemaValidator.validate(schema, schema(arguments)).orElse(""));
    }

    private static String problem(JsonObject schema, String arguments) {
        return SchemaValidator.validate(schema, schema(arguments)).orElse("<valid>");
    }
}
