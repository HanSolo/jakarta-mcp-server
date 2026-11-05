package eu.hansolo.mcp.rest;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import eu.hansolo.mcp.server.McpHttpResponse;
import eu.hansolo.mcp.server.McpRequestHandler;

import java.io.InputStream;

/**
 * The MCP endpoint. POST only: JAX-RS answers GET and DELETE with 405, which is what the 2026-07-28 transport asks for
 * (the GET stream and session termination no longer exist). A stale Mcp-Session-Id header is simply never read.
 */
@Path("/mcp")
@RequestScoped
public class McpResource {

    private static final MediaType JSON_UTF8 = MediaType.APPLICATION_JSON_TYPE.withCharset("utf-8");

    @Inject
    private McpRequestHandler handler;


    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response post(@Context HttpHeaders headers, InputStream body) {
        McpHttpResponse result = handler.handle(headers::getHeaderString, body);
        Response.ResponseBuilder response = Response.status(result.status()).header("X-Content-Type-Options", "nosniff");
        result.headers().forEach(response::header);
        if (result.body() != null) {
            response.type(JSON_UTF8).entity(result.body());
        }
        return response.build();
    }
}
