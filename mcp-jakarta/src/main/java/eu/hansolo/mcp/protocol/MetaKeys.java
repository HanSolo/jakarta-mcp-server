package eu.hansolo.mcp.protocol;

/** Reserved _meta keys of the 2026-07-28 revision. */
public final class MetaKeys {

    /** Key of the protocol version a request is made under. */
    public static final String PROTOCOL_VERSION    = "io.modelcontextprotocol/protocolVersion";
    /** Key of the client name and version. */
    public static final String CLIENT_INFO         = "io.modelcontextprotocol/clientInfo";
    /** Key of the capabilities (and extensions) the client declares for this request. */
    public static final String CLIENT_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities";
    /** Key of the server name and version in results. */
    public static final String SERVER_INFO         = "io.modelcontextprotocol/serverInfo";
    /** Key that ties a notification to a {@code subscriptions/listen} stream. */
    public static final String SUBSCRIPTION_ID     = "io.modelcontextprotocol/subscriptionId";


    private MetaKeys() {
    }
}
