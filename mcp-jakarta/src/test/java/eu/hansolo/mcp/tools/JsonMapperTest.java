package eu.hansolo.mcp.tools;

import jakarta.json.JsonValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonMapperTest {

    record Sum(double sum, Optional<Float> ratio) {
    }

    private static String json(Object value) {
        return JsonMapper.toJson(value).toString();
    }

    @Test
    void wholeDoublesAreWrittenWithoutAFraction() {
        assertEquals("5", json(5.0));
        assertEquals("-3", json(-3.0));
        assertEquals("0", json(0.0));
        assertEquals("100", json(100.0));
        assertEquals("{\"sum\":5}", json(new Sum(5.0, Optional.empty())));
    }

    @Test
    void fractionsKeepTheirDigits() {
        assertEquals("3.5", json(3.5));
        assertEquals("0.1", json(0.1));
    }

    @Test
    void aFloatDoesNotShowItsBinaryTail() {
        assertEquals("0.1", json(0.1f));
        assertEquals("2", json(2.0f));
        assertEquals("{\"sum\":1.5,\"ratio\":0.25}", json(new Sum(1.5, Optional.of(0.25f))));
    }

    @Test
    void integersAndOthersAreUnchanged() {
        assertEquals("7", json(7));
        assertEquals("9999999999", json(9_999_999_999L));
        assertEquals("[1,2.5]", json(List.of(1, 2.5)));
        assertEquals("{\"a\":4}", json(Map.of("a", 4.0)));
    }

    @Test
    void nonFiniteNumbersAreStillRefused() {
        assertThrows(NumberFormatException.class, () -> json(Double.NaN));
        assertThrows(NumberFormatException.class, () -> json(Double.POSITIVE_INFINITY));
    }

    @Test
    void booleansBecomeJsonLiterals() {
        assertEquals(JsonValue.TRUE, JsonMapper.toJson(true));
    }
}
