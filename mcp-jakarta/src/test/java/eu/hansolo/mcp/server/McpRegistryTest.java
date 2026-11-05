package eu.hansolo.mcp.server;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import eu.hansolo.mcp.api.McpToolHandler;
import eu.hansolo.mcp.api.ToolCall;
import eu.hansolo.mcp.api.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class McpRegistryTest {

    @Test
    void rejectsDuplicateNames() {
        assertThrows(IllegalStateException.class, () -> McpRegistry.of(List.of(new Named("a"), new Named("a"))));
    }

    @Test
    void rejectsNamesThatAreNotHeaderSafe() {
        assertThrows(IllegalStateException.class, () -> McpRegistry.of(List.of(new Named("has space"))));
        assertThrows(IllegalStateException.class, () -> McpRegistry.of(List.of(new Named(""))));
    }

    @Test
    void listsToolsSortedByName() {
        McpRegistry registry = McpRegistry.of(List.of(new Named("b"), new Named("a"), new Named("c")));
        assertEquals(List.of("a", "b", "c"), registry.all().stream().map(McpToolHandler::name).toList());
    }

    private record Named(String name) implements McpToolHandler {

        @Override
        public String description() {
            return null;
        }

        @Override
        public JsonObject inputSchema() {
            return Json.createObjectBuilder().add("type", "object").build();
        }

        @Override
        public ToolResult call(ToolCall call) {
            return ToolResult.text(name);
        }
    }
}
