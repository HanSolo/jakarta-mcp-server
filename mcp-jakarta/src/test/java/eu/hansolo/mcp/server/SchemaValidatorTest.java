package eu.hansolo.mcp.server;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.Test;

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
}
