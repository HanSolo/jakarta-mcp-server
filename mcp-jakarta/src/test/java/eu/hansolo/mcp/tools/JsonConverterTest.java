package eu.hansolo.mcp.tools;

import jakarta.json.Json;
import jakarta.json.JsonValue;
import eu.hansolo.mcp.protocol.McpProtocolException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonConverterTest {

    enum Color {
        RED, GREEN
    }


    record Point(int x, Optional<Integer> y) {
    }


    interface Types {
        List<Integer> ints();

        Set<String> strings();

        Map<String, Point> points();

        Optional<String> maybe();
    }

    private static Type type(String method) throws Exception {
        return Types.class.getMethod(method).getGenericReturnType();
    }

    private static Object convert(JsonValue value, Type type) {
        return JsonConverter.argument(value, type, "arg", true);
    }

    private static String message(Runnable action) {
        McpProtocolException e = assertThrows(McpProtocolException.class, action::run);
        assertEquals(200, e.httpStatus());
        return e.getMessage();
    }

    @Test
    void scalarsConvert() {
        assertEquals("hi", convert(Json.createValue("hi"), String.class));
        assertEquals(7, convert(Json.createValue(7), int.class));
        assertEquals(7L, convert(Json.createValue(7), Long.class));
        assertEquals(2.5, convert(Json.createValue(2.5), double.class));
        assertEquals(new BigDecimal("1.50"), convert(Json.createValue(new BigDecimal("1.50")), BigDecimal.class));
        assertEquals(true, convert(JsonValue.TRUE, boolean.class));
        assertEquals(Color.GREEN, convert(Json.createValue("GREEN"), Color.class));
    }

    @Test
    void typeMismatchesNameTheArgument() {
        assertTrue(message(() -> convert(Json.createValue("x"), int.class)).contains("'arg' must be an integer"));
        assertTrue(message(() -> convert(Json.createValue(1.5), int.class)).contains("must be an integer"));
        assertTrue(message(() -> convert(Json.createValue(1), String.class)).contains("must be a string"));
        assertTrue(message(() -> convert(Json.createValue("true"), boolean.class)).contains("must be a boolean"));
        assertTrue(message(() -> convert(Json.createValue("BLUE"), Color.class)).contains("one of [RED, GREEN]"));
    }

    @Test
    void integerRangesAreEnforced() {
        assertTrue(message(() -> convert(Json.createValue(3_000_000_000L), int.class)).contains("out of range"));
        assertTrue(message(() -> convert(Json.createValue(300), byte.class)).contains("out of range"));
        assertEquals((short) 300, convert(Json.createValue(300), short.class));
    }

    @Test
    void missingAndNullArguments() {
        assertTrue(message(() -> JsonConverter.argument(null, String.class, "name", true)).contains("'name' is required"));
        assertTrue(message(() -> JsonConverter.argument(JsonValue.NULL, int.class, "n", true)).contains("is required"));
        assertNull(JsonConverter.argument(null, String.class, "name", false));
        assertEquals(Optional.empty(), JsonConverter.argument(null, Optional.class, "o", false));
        assertEquals(Optional.empty(), JsonConverter.argument(JsonValue.NULL, Optional.class, "o", false));
    }

    @Test
    void collectionsMapsAndArrays() throws Exception {
        Type ints = type("ints");
        Type strings = type("strings");
        assertEquals(List.of(1, 2), convert(Json.createArrayBuilder().add(1).add(2).build(), ints));
        assertEquals(Set.of("a", "b"), convert(Json.createArrayBuilder().add("a").add("b").build(), strings));
        assertArrayEquals(new int[] {3, 4}, (int[]) convert(Json.createArrayBuilder().add(3).add(4).build(), int[].class));
        assertTrue(message(() -> convert(Json.createArrayBuilder().add("a").add("a").build(), strings)).contains("duplicates"));
        assertTrue(message(() -> convert(Json.createArrayBuilder().add(1).add("x").build(), ints)).contains("arg[1]"));
    }

    @Test
    void recordsConvertRecursivelyAndRejectUnknownProperties() throws Exception {
        assertEquals(new Point(1, Optional.empty()), convert(Json.createObjectBuilder().add("x", 1).build(), Point.class));
        assertEquals(new Point(1, Optional.of(2)), convert(Json.createObjectBuilder().add("x", 1).add("y", 2).build(), Point.class));
        assertTrue(message(() -> convert(Json.createObjectBuilder().add("y", 2).build(), Point.class)).contains("'arg.x' is required"));
        assertTrue(message(() -> convert(Json.createObjectBuilder().add("x", 1).add("z", 2).build(), Point.class)).contains("'arg.z' is not a known property"));
        Type points = type("points");
        var nested = Json.createObjectBuilder().add("p", Json.createObjectBuilder().add("x", "bad")).build();
        assertTrue(message(() -> convert(nested, points)).contains("'arg.p.x' must be an integer"));
    }
}
