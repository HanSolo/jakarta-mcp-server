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


    /**
     * A failure with an explicit HTTP status, JSON-RPC error code, message and optional data. Prefer the factory methods.
     *
     * @param httpStatus the HTTP status of the response
     * @param code       the JSON-RPC error code, see {@link ErrorCodes}
     * @param message    the message sent to the client
     * @param data       optional error data, or null
     */
    public McpProtocolException(int httpStatus, int code, String message, JsonValue data) {
        super(message);
        this.httpStatus = httpStatus;
        this.code       = code;
        this.data       = data;
    }


    /** The HTTP status the response carries. */
    public int httpStatus() {
        return httpStatus;
    }

    /** The JSON-RPC error code. */
    public int code() {
        return code;
    }

    /** Optional error data, or null. */
    public JsonValue data() {
        return data;
    }

    /** The body is not valid JSON (HTTP 400, -32700). */
    public static McpProtocolException parseError(String message) {
        return new McpProtocolException(400, ErrorCodes.PARSE_ERROR, message, null);
    }

    /** The body is not a valid JSON-RPC request (HTTP 400, -32600). */
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

    /** The method is unknown (HTTP 404, -32601). */
    public static McpProtocolException methodNotFound(String method) {
        return new McpProtocolException(404, ErrorCodes.METHOD_NOT_FOUND, "Method not found: " + method, null);
    }

    /** A header disagrees with the body (HTTP 400, -32020). */
    public static McpProtocolException headerMismatch(String message) {
        return new McpProtocolException(400, ErrorCodes.HEADER_MISMATCH, message, null);
    }

    /** The requested protocol version is not supported (HTTP 400, -32022); the data lists the supported versions. */
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

    /** The request needs an extension (for example {@code io.modelcontextprotocol/ui}) that the client did not declare. */
    public static McpProtocolException missingClientExtension(String id) {
        JsonObject required = Json.createObjectBuilder().add("extensions", Json.createObjectBuilder().add(id, Json.createObjectBuilder())).build();
        JsonObject data = Json.createObjectBuilder().add("requiredCapabilities", required).build();
        return new McpProtocolException(400, ErrorCodes.MISSING_REQUIRED_CLIENT_CAPABILITY, "Server requires the " + id + " extension for this request", data);
    }

    /** An internal failure (HTTP 500, -32603). The message is sent to the client, so it must not reveal internals. */
    public static McpProtocolException internal(String message) {
        return new McpProtocolException(500, ErrorCodes.INTERNAL_ERROR, message, null);
    }
}
