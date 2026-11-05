package eu.hansolo.mcp.protocol;

/** JSON-RPC and MCP error codes. The -32020..-32099 range is reserved for the MCP specification. */
public final class ErrorCodes {

    public static final int PARSE_ERROR                        = -32700;
    public static final int INVALID_REQUEST                    = -32600;
    public static final int METHOD_NOT_FOUND                   = -32601;
    public static final int INVALID_PARAMS                     = -32602;
    public static final int INTERNAL_ERROR                     = -32603;
    public static final int HEADER_MISMATCH                    = -32020;
    public static final int MISSING_REQUIRED_CLIENT_CAPABILITY = -32021;
    public static final int UNSUPPORTED_PROTOCOL_VERSION       = -32022;


    private ErrorCodes() {
    }
}
