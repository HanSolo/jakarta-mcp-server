package eu.hansolo.mcp.itfixture;

import jakarta.enterprise.context.ApplicationScoped;
import eu.hansolo.mcp.api.ToolCall;
import eu.hansolo.mcp.api.ToolExceptionMapper;

import java.util.Optional;

/** Explains {@code IllegalArgumentException} to the model and leaves everything else to the library. */
@ApplicationScoped
public class FixtureFailureMapper implements ToolExceptionMapper {

    @Override
    public Optional<String> message(Throwable failure, ToolCall call) {
        if (failure instanceof IllegalArgumentException) {
            return Optional.of(failure.getMessage());
        }
        return Optional.empty();
    }
}
