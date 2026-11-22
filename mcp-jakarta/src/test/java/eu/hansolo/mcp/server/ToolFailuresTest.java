package eu.hansolo.mcp.server;

import eu.hansolo.mcp.api.ToolCall;
import eu.hansolo.mcp.api.ToolExceptionMapper;
import eu.hansolo.mcp.api.ToolExecutionException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolFailuresTest {

    private static final ToolCall CALL = new ToolCall("duck_book", null, null, null, null, null, null);


    private static ToolExceptionMapper answering(String text) {
        return (failure, call) -> Optional.ofNullable(text);
    }

    @Test
    void anUnmappedExceptionIsReplacedByTheGenericText() {
        assertEquals(ToolFailures.GENERIC, ToolFailures.none().message(new IllegalStateException("secret detail"), CALL));
    }

    @Test
    void aToolExecutionExceptionShowsItsOwnMessage() {
        assertEquals("No such duck", ToolFailures.none().message(new ToolExecutionException("No such duck"), CALL));
    }

    @Test
    void aToolExecutionExceptionWithoutMessageGivesTheGenericText() {
        assertEquals(ToolFailures.GENERIC, ToolFailures.none().message(new ToolExecutionException(null), CALL));
    }

    @Test
    void wrappersAreRemovedBeforeTheMappersLook() {
        List<Throwable> seen = new ArrayList<>();
        ToolFailures failures = new ToolFailures(List.of((failure, call) -> {
            seen.add(failure);
            return Optional.empty();
        }));
        IllegalArgumentException root = new IllegalArgumentException("bad");
        failures.message(new InvocationTargetException(new CompletionException(new ExecutionException(root))), CALL);
        assertEquals(List.of(root), seen);
    }

    @Test
    void aWrappedToolExecutionExceptionStillShowsItsMessage() {
        assertEquals("visible", ToolFailures.none().message(new InvocationTargetException(new ToolExecutionException("visible")), CALL));
    }

    @Test
    void theFirstMapperWithAnAnswerWins() {
        ToolFailures failures = new ToolFailures(List.of(answering(null), answering("second"), answering("third")));
        assertEquals("second", failures.message(new RuntimeException(), CALL));
    }

    @Test
    void emptyAndBlankAnswersAndNullsAreSkipped() {
        ToolExceptionMapper nullAnswer = (failure, call) -> null;
        ToolFailures failures = new ToolFailures(List.of(nullAnswer, answering(" "), answering("fallback")));
        assertEquals("fallback", failures.message(new RuntimeException(), CALL));
    }

    @Test
    void aMapperThatThrowsIsSkipped() {
        ToolExceptionMapper broken = (failure, call) -> {
            throw new IllegalStateException("mapper bug");
        };
        ToolFailures failures = new ToolFailures(List.of(broken, answering("next")));
        assertEquals("next", failures.message(new RuntimeException(), CALL));
    }

    @Test
    void aMapperCanDecideByTheCall() {
        ToolFailures failures = new ToolFailures(List.of((failure, call) -> "duck_book".equals(call.name()) ? Optional.of("Booking failed") : Optional.empty()));
        assertEquals("Booking failed", failures.message(new RuntimeException(), CALL));
    }

    @Test
    void aLongMessageIsCut() {
        String message = ToolFailures.none().message(new ToolExecutionException("x".repeat(5000)), CALL);
        assertEquals(ToolFailures.MAX_MESSAGE, message.length());
        assertTrue(message.endsWith("…"));
    }

    @Test
    void aMessageAtTheLimitIsKept() {
        String text = "y".repeat(ToolFailures.MAX_MESSAGE);
        assertEquals(text, new ToolFailures(List.of(answering(text))).message(new RuntimeException(), CALL));
    }

    @Test
    void aCauseCycleDoesNotLoopForever() {
        Throwable a = new CompletionException() {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        assertEquals(ToolFailures.GENERIC, ToolFailures.none().message(a, CALL));
    }
}
