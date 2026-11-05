package eu.hansolo.mcp.tools;

import jakarta.json.JsonObject;
import eu.hansolo.mcp.api.McpParam;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaGeneratorTest {

    enum Color {
        RED, GREEN
    }


    record Address(@McpParam(description = "Street and number") String street, Optional<String> zip) {
    }


    record Person(String name, int age, Address address, List<String> tags, Color favourite) {
    }


    record Loop(Loop next) {
    }


    interface Types {
        List<Integer> ints();

        Set<String> uniques();

        Map<String, Double> scores();

        Optional<Long> maybe();
    }

    private static Type returnType(String method) throws Exception {
        return Types.class.getMethod(method).getGenericReturnType();
    }

    @Test
    void scalars() {
        assertEquals("string", SchemaGenerator.schemaFor(String.class).getString("type"));
        assertEquals("boolean", SchemaGenerator.schemaFor(boolean.class).getString("type"));
        assertEquals("integer", SchemaGenerator.schemaFor(int.class).getString("type"));
        assertEquals("integer", SchemaGenerator.schemaFor(Long.class).getString("type"));
        assertEquals("number", SchemaGenerator.schemaFor(double.class).getString("type"));
        assertEquals("number", SchemaGenerator.schemaFor(BigDecimal.class).getString("type"));
    }

    @Test
    void enumsListTheirConstants() {
        JsonObject schema = SchemaGenerator.schemaFor(Color.class);
        assertEquals("string", schema.getString("type"));
        assertEquals("GREEN", schema.getJsonArray("enum").getString(1));
    }

    @Test
    void collectionsAndMaps() throws Exception {
        assertEquals("integer", SchemaGenerator.schemaFor(returnType("ints")).getJsonObject("items").getString("type"));
        assertTrue(SchemaGenerator.schemaFor(returnType("uniques")).getBoolean("uniqueItems"));
        assertEquals("number", SchemaGenerator.schemaFor(returnType("scores")).getJsonObject("additionalProperties").getString("type"));
        assertEquals("integer", SchemaGenerator.schemaFor(returnType("maybe")).getString("type"));
        assertEquals("string", SchemaGenerator.schemaFor(String[].class).getJsonObject("items").getString("type"));
    }

    @Test
    void recordsBecomeClosedObjectsWithRequiredNonOptionalComponents() {
        JsonObject schema = SchemaGenerator.schemaFor(Person.class);
        assertEquals("object", schema.getString("type"));
        assertFalse(schema.getBoolean("additionalProperties"));
        assertEquals(List.of("name", "age", "address", "tags", "favourite"), schema.getJsonArray("required").stream().map(v -> ((jakarta.json.JsonString) v).getString()).toList());
        JsonObject address = schema.getJsonObject("properties").getJsonObject("address");
        assertEquals("Street and number", address.getJsonObject("properties").getJsonObject("street").getString("description"));
        assertEquals(1, address.getJsonArray("required").size());
    }

    @Test
    void recursiveRecordsAreRejectedWithAClearMessage() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> SchemaGenerator.schemaFor(Loop.class));
        assertTrue(e.getMessage().contains("recursive"));
    }

    @Test
    void unsupportedTypesNameTheType() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> SchemaGenerator.schemaFor(Thread.class));
        assertTrue(e.getMessage().contains("java.lang.Thread"));
        assertThrows(IllegalStateException.class, () -> SchemaGenerator.schemaFor(List.class));
    }

    @Test
    void optionalAndPrimitiveRequirednessRules() {
        assertFalse(SchemaGenerator.isRequired(Optional.class, null));
        assertTrue(SchemaGenerator.isRequired(String.class, null));
        assertTrue(SchemaGenerator.isRequired(int.class, null));
    }
}
