package eu.hansolo.mcp.protocol;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;

/**
 * A protocol-level failure that is reported as a JSON-RPC error response. Tools may throw it too (for example
 * {@link #missingClientCapability(String)}); any other exception thrown by a tool becomes an {@code isError} tool result.
 */
public class McpProtocolException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int                 httpStatus;
    private final int                 code;
    private final transient JsonValue data;


    public McpProtocolException(int httpStatus, int code, String message, JsonValue data) {
        super(message);
        this.httpStatus = httpStatus;
        this.code       = code;
        this.data       = data;
    }


    public int httpStatus() {
        return httpStatus;
    }

    public int code() {
        return code;
    }

    /** Optional error data, or null. */
    public JsonValue data() {
        return data;
    }

    public static McpProtocolException parseError(String message) {
        return new McpProtocolException(400, ErrorCodes.PARSE_ERROR, message, null);
    }

    public static McpProtocolException invalidRequest(String message) {
        return new McpProtocolException(400, ErrorCodes.INVALID_REQUEST, message, null);
    }

    /** Malformed request parameters or _meta: the spec requires HTTP 400. */
    public static McpProtocolException malformedParams(String message) {
        return new McpProtocolException(400, ErrorCodes.INVALID_PARAMS, message, null);
    }

    /** Well-formed request whose arguments are unacceptable (unknown tool, bad argument, bad cursor): reported on an HTTP 200. */
    public static McpProtocolException invalidArguments(String message) {
        return new McpProtocolException(200, ErrorCodes.INVALID_PARAMS, message, null);
    }

    public static McpProtocolException methodNotFound(String method) {
        return new McpProtocolException(404, ErrorCodes.METHOD_NOT_FOUND, "Method not found: " + method, null);
    }

    public static McpProtocolException headerMismatch(String message) {
        return new McpProtocolException(400, ErrorCodes.HEADER_MISMATCH, message, null);
    }

    public static McpProtocolException unsupportedVersion(String requested) {
        JsonArrayBuilder supported = Json.createArrayBuilder();
        McpVersions.SUPPORTED.forEach(supported::add);
        JsonObject data = Json.createObjectBuilder().add("supported", supported).add("requested", requested).build();
        return new McpProtocolException(400, ErrorCodes.UNSUPPORTED_PROTOCOL_VERSION, "Unsupported protocol version", data);
    }

    /** The tool needs a client capability (for example "elicitation") that the request did not declare. */
    public static McpProtocolException missingClientCapability(String capability) {
        JsonObject required = Json.createObjectBuilder().add(capability, Json.createObjectBuilder()).build();
        JsonObject data = Json.createObjectBuilder().add("requiredCapabilities", required).build();
        return new McpProtocolException(400, ErrorCodes.MISSING_REQUIRED_CLIENT_CAPABILITY, "Server requires the " + capability + " capability for this request", data);
    }

    public static McpProtocolException internal(String message) {
        return new McpProtocolException(500, ErrorCodes.INTERNAL_ERROR, message, null);
    }
}
