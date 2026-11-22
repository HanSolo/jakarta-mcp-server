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
        if (isResponse(value.getClass())) {
            return fromResponse(value);
        }
        if (ValueTypes.isValue(value)) {
            return ToolResult.text(ValueTypes.format(value));
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

    /** True if the class is, or extends, {@code jakarta.ws.rs.core.Response} (by name, so that JAX-RS need not be on the class path). */
    private static boolean isResponse(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            if (c.getName().equals("jakarta.ws.rs.core.Response")) {
                return true;
            }
        }
        return false;
    }

    private static ToolResult fromResponse(Object response) {
        try {
            return JaxRsResponse.map(response);
        } catch (LinkageError e) {
            throw new IllegalStateException("Cannot read a JAX-RS Response: " + e, e);
        }
    }

    /** True if results of this declared type carry structured content and so should advertise an outputSchema. */
    public static boolean hasOutputSchema(Class<?> returnType) {
        return returnType.isRecord() || Beans.isBean(returnType);
    }


    /**
     * A JAX-RS {@code Response}: a status below 400 is a success whose entity is mapped like any other result (no entity is an empty
     * result); a 4xx status is an error result with the entity as the message (the reason phrase if there is none), because a status
     * like that is written for the caller; a 5xx status is the generic error, so that internals stay in the log. Kept apart, so that the
     * class loads only where JAX-RS is present.
     */
    private static final class JaxRsResponse {

        private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(ResultMapper.class.getName());

        static ToolResult map(Object value) {
            jakarta.ws.rs.core.Response response = (jakarta.ws.rs.core.Response) value;
            int status = response.getStatus();
            Object entity = response.hasEntity() ? response.getEntity() : null;
            if (status < 400) {
                return entity == null ? new ToolResult.Complete(Json.createArrayBuilder().build(), null, false) : toResult(entity);
            }
            if (status >= 500) {
                LOG.warning("A tool returned a Response with status " + status + (entity == null ? "" : " and an entity of " + entity.getClass().getName()));
                return ToolResult.error("Tool execution failed");
            }
            return ToolResult.error(message(response, status, entity));
        }

        private static String message(jakarta.ws.rs.core.Response response, int status, Object entity) {
            if (entity instanceof CharSequence text && !text.toString().isBlank()) {
                return text.toString();
            }
            if (entity != null && !(entity instanceof java.io.InputStream) && !(entity instanceof jakarta.ws.rs.core.StreamingOutput)) {
                try {
                    return JsonMapper.toJson(entity).toString();
                } catch (RuntimeException e) {
                    // an entity that cannot be written: the status says enough
                }
            }
            String reason = response.getStatusInfo() == null ? "" : response.getStatusInfo().getReasonPhrase();
            return reason == null || reason.isBlank() ? "HTTP " + status : "HTTP " + status + " " + reason;
        }
    }
}
