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


    record Node(String label, Optional<Node> next, List<Node> children) {
    }


    record Wrapper(String name, Node root) {
    }


    record Ping(Optional<Pong> pong) {
    }


    record Pong(Optional<Ping> ping) {
    }


    static class A {
        record Item(Optional<Item> more) {
        }
    }


    static class B {
        record Item(Optional<Item> more) {
        }
    }


    record Both(A.Item first, B.Item second) {
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
    void aRecursiveRecordIsDescribedWithDefsAndRef() {
        JsonObject schema = SchemaGenerator.schemaFor(Node.class);
        assertEquals("#/$defs/Node", schema.getString("$ref"));
        JsonObject node = schema.getJsonObject("$defs").getJsonObject("Node");
        assertEquals("object", node.getString("type"));
        assertEquals(List.of("label", "children"), node.getJsonArray("required").stream().map(v -> ((jakarta.json.JsonString) v).getString()).toList());
        assertEquals("#/$defs/Node", node.getJsonObject("properties").getJsonObject("next").getString("$ref"));
        assertEquals("#/$defs/Node", node.getJsonObject("properties").getJsonObject("children").getJsonObject("items").getString("$ref"));
        assertEquals(1, schema.getJsonObject("$defs").size());
    }

    @Test
    void aRecordThatContainsARecursiveOneStaysInlineAndSharesTheDefinition() {
        JsonObject schema = SchemaGenerator.schemaFor(Wrapper.class);
        assertEquals("object", schema.getString("type"));
        assertEquals("#/$defs/Node", schema.getJsonObject("properties").getJsonObject("root").getString("$ref"));
        assertTrue(schema.getJsonObject("$defs").containsKey("Node"));
        assertFalse(schema.getJsonObject("$defs").containsKey("Wrapper"));
    }

    @Test
    void recordsThatReferToEachOtherBothGetADefinition() {
        JsonObject schema = SchemaGenerator.schemaFor(Ping.class);
        assertEquals(java.util.Set.of("Ping", "Pong"), schema.getJsonObject("$defs").keySet());
        assertEquals("#/$defs/Pong", schema.getJsonObject("$defs").getJsonObject("Ping").getJsonObject("properties").getJsonObject("pong").getString("$ref"));
        assertEquals("#/$defs/Ping", schema.getJsonObject("$defs").getJsonObject("Pong").getJsonObject("properties").getJsonObject("ping").getString("$ref"));
    }

    @Test
    void twoRecursiveRecordsWithTheSameSimpleNameKeepApartInTheDefinitions() {
        JsonObject defs = SchemaGenerator.schemaFor(Both.class).getJsonObject("$defs");
        assertEquals(2, defs.size());
        assertTrue(defs.containsKey("Item"));
        assertTrue(defs.keySet().stream().anyMatch(name -> name.startsWith("eu.hansolo.mcp.tools.SchemaGeneratorTest$")));
    }

    @Test
    void definitionsOfPartsAreCollectedInOneSharedObject() {
        SchemaGenerator.Defs defs = new SchemaGenerator.Defs();
        JsonObject first = SchemaGenerator.schemaFor(Node.class, defs);
        JsonObject second = SchemaGenerator.schemaFor(Wrapper.class, defs);
        assertFalse(first.containsKey("$defs"));
        assertFalse(second.containsKey("$defs"));
        assertEquals(java.util.Set.of("Node"), defs.toJson().keySet());
    }

    @Test
    void aTypeWithoutRecursionHasNoDefs() {
        assertFalse(SchemaGenerator.schemaFor(Person.class).containsKey("$defs"));
        assertTrue(new SchemaGenerator.Defs().isEmpty());
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
