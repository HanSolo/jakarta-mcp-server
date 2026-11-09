package eu.hansolo.mcp.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HeaderValuesTest {

    @Test
    void plainValuesPassThrough() {
        assertEquals("get_weather", HeaderValues.decode("get_weather"));
    }

    @Test
    void decodesTheBase64Sentinel() {
        assertEquals("Hello, 世界", HeaderValues.decode("=?base64?SGVsbG8sIOS4lueVjA==?="));
    }

    @Test
    void rejectsMalformedBase64() {
        assertThrows(IllegalArgumentException.class, () -> HeaderValues.decode("=?base64?***?="));
        assertThrows(IllegalArgumentException.class, () -> HeaderValues.decode("=?base64?SGVsbG8?="));
        assertThrows(IllegalArgumentException.class, () -> HeaderValues.decode("=?base64?SGVsbG8sIOS4lueVjA?="));
    }
}
