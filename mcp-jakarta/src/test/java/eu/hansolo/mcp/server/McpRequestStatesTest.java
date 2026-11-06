package eu.hansolo.mcp.server;

import eu.hansolo.mcp.protocol.McpProtocolException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpRequestStatesTest {

    private final McpRequestStates states = new McpRequestStates("a-test-secret", 60_000L);


    @Test
    void roundTrip() {
        assertEquals("step=2;name=Ada", states.require(states.sign("step=2;name=Ada")));
    }

    @Test
    void payloadWithDotsAndUnicodeSurvives() {
        String payload = "a.b.c é ✓";
        assertEquals(payload, states.require(states.sign(payload)));
    }

    @Test
    void appendedTextIsRejected() {
        assertTrue(states.verify(states.sign("x") + "-TAMPERED").isEmpty());
    }

    @Test
    void alteredPayloadIsRejected() {
        String[] parts = states.sign("amount=1").split("\\.");
        String forged = parts[0] + "." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("amount=999".getBytes()) + "." + parts[2];
        assertTrue(states.verify(forged).isEmpty());
    }

    @Test
    void expiredStateIsRejected() {
        assertTrue(states.verify(states.sign("x", "", System.currentTimeMillis() - 1)).isEmpty());
    }

    @Test
    void contextIsBound() {
        String state = states.sign("x", "tool-a|alice");
        assertEquals("x", states.require(state, "tool-a|alice"));
        assertTrue(states.verify(state, "tool-b|alice").isEmpty());
        assertTrue(states.verify(state).isEmpty());
    }

    @Test
    void anotherKeyIsRejected() {
        assertTrue(new McpRequestStates("other-secret", 60_000L).verify(states.sign("x")).isEmpty());
    }

    @Test
    void garbageIsRejected() {
        for (String junk : new String[]{null, "", "a", "a.b", "a.b.c", "1.2.3.4", "x.!!!.!!!", ".."}) {
            assertTrue(states.verify(junk).isEmpty(), String.valueOf(junk));
        }
    }

    @Test
    void requireReportsInvalidParamsWithHttp200() {
        McpProtocolException e = assertThrows(McpProtocolException.class, () -> states.require("nonsense"));
        assertEquals(200, e.httpStatus());
        assertEquals(-32602, e.code());
    }

    @Test
    void randomKeyWorksWithinOneInstance() {
        McpRequestStates random = new McpRequestStates(new McpConfig());
        assertEquals("x", random.require(random.sign("x")));
    }
}
