package eu.hansolo.mcp.auth;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;

/** The RFC 9728 document that tells clients which authorization server(s) to use for this MCP endpoint. */
public final class ProtectedResourceMetadata {

    private ProtectedResourceMetadata() {
    }


    public static JsonObject of(AuthConfig config) {
        JsonArrayBuilder servers = Json.createArrayBuilder();
        config.authorizationServers().forEach(servers::add);
        JsonObjectBuilder document = Json.createObjectBuilder().add("resource", config.resource()).add("authorization_servers", servers);
        if (!config.scopesSupported().isEmpty()) {
            JsonArrayBuilder scopes = Json.createArrayBuilder();
            config.scopesSupported().forEach(scopes::add);
            document.add("scopes_supported", scopes);
        }
        return document.add("bearer_methods_supported", Json.createArrayBuilder().add("header")).build();
    }
}
