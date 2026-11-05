package eu.hansolo.mcp.rest;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import eu.hansolo.mcp.auth.AuthConfig;
import eu.hansolo.mcp.auth.ProtectedResourceMetadata;

/**
 * Serves {@code /.well-known/oauth-protected-resource[/path]} (RFC 9728). The well-known prefix is only reachable when the
 * application is deployed at the host root (Payara: ROOT.war or context-root "/"); behind a reverse proxy, map it through.
 * Answers 404 unless authentication is enabled and the suffix equals the path of {@code mcp.auth.resource}.
 */
@Path("/.well-known/oauth-protected-resource{suffix:(/.*)?}")
@RequestScoped
public class ProtectedResourceMetadataResource {

    @Inject
    private AuthConfig config;


    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response get(@PathParam("suffix") String suffix) {
        String requested = suffix == null ? "" : suffix;
        if (!config.enabled() || !requested.equals(config.resourcePath())) {
            return Response.status(404).build();
        }
        return Response.ok(ProtectedResourceMetadata.of(config).toString(), MediaType.APPLICATION_JSON_TYPE.withCharset("utf-8")).header("Cache-Control", "public, max-age=300").header("X-Content-Type-Options", "nosniff").build();
    }
}
