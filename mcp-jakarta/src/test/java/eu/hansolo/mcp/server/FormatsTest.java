package eu.hansolo.mcp.server;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormatsTest {

    private static void accepts(String format, String... values) {
        for (String value : values) {
            assertTrue(Formats.valid(format, value), format + " should accept '" + value + "'");
        }
    }

    private static void rejects(String format, String... values) {
        for (String value : values) {
            assertFalse(Formats.valid(format, value), format + " should reject '" + value + "'");
        }
    }

    @Test
    void dateTime() {
        accepts("date-time", "2026-11-11T08:17:11Z", "2026-11-11t08:17:11.123+01:00", "2024-02-29T00:00:00-05:00");
        rejects("date-time", "2026-11-11", "2026-11-11T25:00:00Z", "2026-11-11T08:17Z", "2026-02-30T00:00:00Z", "2026-11-11T08:17:11", "yesterday");
    }

    @Test
    void date() {
        accepts("date", "2026-11-11", "2024-02-29");
        rejects("date", "2026-13-01", "2026-02-30", "2026-02-29", "11/11/2026", "2026-1-1");
    }

    @Test
    void time() {
        accepts("time", "08:17:11Z", "08:17:11.5+01:00");
        rejects("time", "08:17", "08:17:11", "25:00:00Z");
    }

    @Test
    void duration() {
        accepts("duration", "P1Y2M3DT4H5M6S", "PT36H", "P2W", "PT1M5S", "P1D");
        rejects("duration", "P", "1Y", "P1Y2W", "PT", "P1DT", "P-1D");
    }

    @Test
    void email() {
        accepts("email", "a@b.example", "a.b+c@example.com", "a@[127.0.0.1]", "a@[IPv6:::1]");
        rejects("email", "no-at", "@x.com", "a@", "a..b@x.com", "a b@x.com", ".a@x.com", "a@-x.com");
    }

    @Test
    void hostname() {
        accepts("hostname", "example.com", "a-b.example", "localhost");
        rejects("hostname", "-a.com", "a..b", "a_b.com", "", "a.com.");
    }

    @Test
    void ipv4() {
        accepts("ipv4", "192.168.0.1", "0.0.0.0", "255.255.255.255");
        rejects("ipv4", "256.1.1.1", "1.2.3", "01.2.3.4", "1.2.3.4.5", "a.b.c.d");
    }

    @Test
    void ipv6() {
        accepts("ipv6", "::1", "::", "2001:db8::8a2e:370:7334", "::ffff:192.0.2.1", "1:2:3:4:5:6:7:8", "1:2:3:4:5:6:1.2.3.4", "fe80::");
        rejects("ipv6", "1:2:3:4:5:6:7", "1::2::3", ":1:2:3:4:5:6:7", "12345::", "1:2:3:4:5:6:7:8:9", ":::", "g::1", "1", "");
    }

    @Test
    void uri() {
        accepts("uri", "https://example.com/a?b=c#d", "urn:isbn:123", "mailto:a@b.example");
        rejects("uri", "/relative", "not a uri", "http://exa mple.com", "", "http://exämple.com");
        accepts("uri-reference", "/relative", "a/b?c", "", "https://example.com");
        rejects("uri-reference", "a b", "http://exa mple.com");
    }

    @Test
    void uuid() {
        accepts("uuid", "123e4567-e89b-12d3-a456-426614174000", "123E4567-E89B-12D3-A456-426614174000");
        rejects("uuid", "123e4567e89b12d3a456426614174000", "123e4567-e89b-12d3-a456-42661417400", "not-a-uuid");
    }

    @Test
    void jsonPointerAndRegex() {
        accepts("json-pointer", "", "/a/b", "/a~0b/c~1d", "/");
        rejects("json-pointer", "a/b", "/a~2");
        accepts("regex", "^a+$", "[a-z]{2,3}");
        rejects("regex", "(", "[a-");
    }

    @Test
    void unknownFormatsAreAccepted() {
        assertTrue(Formats.valid("made-up", "anything"));
        assertTrue(Formats.valid("idn-email", "anything"));
        assertTrue(List.of("a", "b").stream().allMatch(v -> Formats.valid("color", v)));
    }
}
