package eu.hansolo.mcp.tools;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import eu.hansolo.mcp.api.ToolResult;


/**
 * Turns what a tool method returned into a {@link ToolResult}.
 *
 * <p><b>Internal.</b> Not part of the stable API: this class is public only because other packages of this library use it,
 * and it can change in any release. Applications should not call it.
 */
public final class ResultMapper {

    private ResultMapper() {
    }


    public static ToolResult toResult(Object value) {
        if (value instanceof ToolResult result) {
            return result;
        }
        if (value == null) {
            return new ToolResult.Complete(Json.createArrayBuilder().build(), null, false);
        }
        if (value instanceof CharSequence || value instanceof Number || value instanceof Boolean || value instanceof Enum<?>) {
            return ToolResult.text(value instanceof Enum<?> constant ? constant.name() : value.toString());
        }
        JsonValue json = JsonMapper.toJson(value);
        if (json instanceof JsonObject object) {
            return ToolResult.structured(object);
        }
        return ToolResult.text(json.toString());
    }

    /** True if results of this declared type carry structured content and so should advertise an outputSchema. */
    public static boolean hasOutputSchema(Class<?> returnType) {
        return returnType.isRecord();
    }
}
