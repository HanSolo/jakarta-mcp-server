package eu.hansolo.mcp.rest;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import eu.hansolo.mcp.server.McpHttpResponse;
import eu.hansolo.mcp.server.McpRequestHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The MCP endpoint. POST only: JAX-RS answers GET and DELETE with 405, which is what the 2026-07-28 transport asks for
 * (the GET stream and session termination no longer exist). A stale Mcp-Session-Id header is simply never read.
 *
 * <p>Most replies are one JSON object. A {@code tools/call} with a {@code progressToken} from a client that accepts
 * {@code text/event-stream} is answered as a stream of Server-Sent Events instead: progress notifications, then the JSON-RPC
 * response as the last event.
 */
@Path("/mcp")
@RequestScoped
public class McpResource {

    private static final MediaType JSON_UTF8  = MediaType.APPLICATION_JSON_TYPE.withCharset("utf-8");
    private static final String    EVENT_TYPE = "text/event-stream;charset=utf-8";

    private static final Logger LOG = Logger.getLogger(McpResource.class.getName());

    @Inject
    private McpRequestHandler handler;


    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response post(@Context HttpHeaders headers, @Context HttpServletResponse servlet, InputStream body) {
        McpHttpResponse result = handler.handle(headers::getHeaderString, body);
        if (result.isStreaming()) {
            return stream(servlet, result.stream());
        }
        Response.ResponseBuilder response = Response.status(result.status()).header("X-Content-Type-Options", "nosniff");
        result.headers().forEach(response::header);
        if (result.body() != null) {
            response.type(JSON_UTF8).entity(result.body());
        }
        return response.build();
    }

    /**
     * Writes the events straight to the servlet response. A JAX-RS {@code StreamingOutput} would not do: Jersey keeps the entity in
     * a buffer until it is full or complete and ignores {@code flush()}, so every event would arrive at the end (observed on Payara).
     * The servlet stream sends each flushed event at once. Each event is {@code event: message}, one {@code data:} line per line of
     * JSON. If the client has gone, the failed write ends the stream quietly: closing it is how a client cancels.
     */
    private static Response stream(HttpServletResponse servlet, McpHttpResponse.Streaming events) {
        servlet.setStatus(200);
        servlet.setContentType(EVENT_TYPE);
        servlet.setHeader("Cache-Control", "no-cache, no-transform");
        servlet.setHeader("X-Accel-Buffering", "no");
        servlet.setHeader("X-Content-Type-Options", "nosniff");
        try {
            servlet.flushBuffer();
            OutputStream output = servlet.getOutputStream();
            events.writeTo(json -> {
                StringBuilder event = new StringBuilder("event: message\n");
                for (String line : json.split("\r\n|\r|\n", -1)) {
                    event.append("data: ").append(line).append('\n');
                }
                output.write(event.append('\n').toString().getBytes(StandardCharsets.UTF_8));
                output.flush();
            });
        } catch (IOException e) {
            LOG.log(Level.FINE, "The client closed the stream", e);
        }
        // everything has been written already; JAX-RS only needs something to return, and a committed response ignores its status and headers
        return Response.status(200).build();
    }
}