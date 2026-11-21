package eu.hansolo.mcp.protocol;

/** JSON-RPC and MCP error codes. The -32020..-32099 range is reserved for the MCP specification. */
public final class ErrorCodes {

    /** The request body is not valid JSON (JSON-RPC). */
    public static final int PARSE_ERROR                        = -32700;
    /** The request is not a valid JSON-RPC request (JSON-RPC). */
    public static final int INVALID_REQUEST                    = -32600;
    /** The method does not exist (JSON-RPC). */
    public static final int METHOD_NOT_FOUND                   = -32601;
    /** The parameters are invalid (JSON-RPC); also used for unknown tools, bad arguments and bad cursors. */
    public static final int INVALID_PARAMS                     = -32602;
    /** An internal error (JSON-RPC); the message does not reveal the cause. */
    public static final int INTERNAL_ERROR                     = -32603;
    /** A standard header ({@code Mcp-Method}, {@code Mcp-Name}, {@code Mcp-Param-*}, {@code MCP-Protocol-Version}) disagrees with the request body. */
    public static final int HEADER_MISMATCH                    = -32020;
    /** The request needs a client capability or extension that the client did not declare. */
    public static final int MISSING_REQUIRED_CLIENT_CAPABILITY = -32021;
    /** The client asked for a protocol version this server does not speak; the error data lists the supported ones. */
    public static final int UNSUPPORTED_PROTOCOL_VERSION       = -32022;


    private ErrorCodes() {
    }
}
