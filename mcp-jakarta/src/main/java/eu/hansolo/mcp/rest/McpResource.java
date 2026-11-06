package eu.hansolo.mcp.rest;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.container.Suspended;
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
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The MCP endpoint. POST only: JAX-RS answers GET and DELETE with 405, which is what the 2026-07-28 transport asks for
 * (the GET stream and session termination no longer exist). A stale Mcp-Session-Id header is simply never read.
 *
 * <p>Most replies are one JSON object. A {@code tools/call} with a {@code progressToken} from a client that accepts
 * {@code text/event-stream} is answered as a stream of Server-Sent Events instead: progress notifications, then the JSON-RPC
 * response as the last event. So is {@code subscriptions/listen}, which stays open until the client or the server closes it.
 */
@Path("/mcp")
@RequestScoped
public class McpResource {

    private static final MediaType JSON_UTF8  = MediaType.APPLICATION_JSON_TYPE.withCharset("utf-8");
    private static final String    EVENT_TYPE = "text/event-stream;charset=utf-8";

    private static final Logger LOG = Logger.getLogger(McpResource.class.getName());

    @Inject
    private McpRequestHandler handler;

    @Inject
    private ListenExecutor listeners;


    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public void post(@Context HttpHeaders headers, @Context HttpServletRequest request, @Context HttpServletResponse servlet, @Suspended AsyncResponse async, InputStream body) {
        noTimeout(request);
        McpHttpResponse result;
        try {
            result = handler.handle(headers::getHeaderString, body);
        } catch (RuntimeException e) {
            async.resume(e);
            return;
        }
        if (!result.isStreaming()) {
            async.resume(plain(result));
        } else if (result.isDetached()) {
            detach(servlet, async, result.stream());
        } else {
            async.resume(stream(servlet, result.stream()));
        }
    }

    /**
     * Suspending the request starts a servlet async cycle with the container's default timeout (typically 30 seconds). A tool
     * that streams progress or a listen stream can run longer, so the timeout is switched off: a stream ends by itself, or when the
     * client goes away.
     */
    private static void noTimeout(HttpServletRequest request) {
        try {
            if (request.isAsyncStarted()) {
                request.getAsyncContext().setTimeout(0);
            }
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "Could not switch off the async timeout", e);
        }
        }

    private static Response plain(McpHttpResponse result) {
        Response.ResponseBuilder response = Response.status(result.status()).header("X-Content-Type-Options", "nosniff");
        result.headers().forEach(response::header);
        if (result.body() != null) {
            response.type(JSON_UTF8).entity(result.body());
        }
        return response.build();
    }

    /**
     * Commits the SSE response, then lets the stream run on its own (virtual) thread so the request thread goes back to the
     * container. Only the output stream is handed over: the servlet response itself belongs to this request thread. The request stays
     * suspended until the stream ends (token expiry, shutdown, client gone).
     */
    private void detach(HttpServletResponse servlet, AsyncResponse async, McpHttpResponse.Streaming events) {
        OutputStream output;
        try {
            begin(servlet);
            output = servlet.getOutputStream();
        } catch (IOException e) {
            LOG.log(Level.FINE, "The client closed the stream", e);
            async.resume(Response.status(200).build());
            return;
        }
        Executor executor = listeners.executor();
        OutputStream target = output;
        try {
            executor.execute(() -> {
                try {
                    events.writeTo(sink(target));
                } catch (IOException e) {
                    LOG.log(Level.FINE, "The client closed the stream", e);
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "A listen stream failed", e);
                } finally {
                    async.resume(Response.status(200).build());
                }
            });
        } catch (RejectedExecutionException e) {
            LOG.log(Level.WARNING, "No thread available for a listen stream", e);
            async.resume(Response.status(200).build());
        }
    }

    /**
     * Writes the events straight to the servlet response. A JAX-RS {@code StreamingOutput} would not do: Jersey keeps the entity in
     * a buffer until it is full or complete and ignores {@code flush()}, so every event would arrive at the end (observed on Payara).
     * The servlet stream sends each flushed event at once. If the client has gone, the failed write ends the stream quietly:
     * closing it is how a client cancels.
     */
    private static Response stream(HttpServletResponse servlet, McpHttpResponse.Streaming events) {
        try {
            begin(servlet);
            events.writeTo(sink(servlet.getOutputStream()));
        } catch (IOException e) {
            LOG.log(Level.FINE, "The client closed the stream", e);
        }
        // everything has been written already; JAX-RS only needs something to return, and a committed response ignores its status and headers
        return Response.status(200).build();
    }

    /** Sends the status and headers of an event stream. */
    private static void begin(HttpServletResponse servlet) throws IOException {
        servlet.setStatus(200);
        servlet.setContentType(EVENT_TYPE);
        servlet.setHeader("Cache-Control", "no-cache, no-transform");
        servlet.setHeader("X-Accel-Buffering", "no");
        servlet.setHeader("X-Content-Type-Options", "nosniff");
            servlet.flushBuffer();
    }

    /** Each event is {@code event: message}, one {@code data:} line per line of JSON; every write is flushed at once. */
    private static McpHttpResponse.EventSink sink(OutputStream output) {
        return new McpHttpResponse.EventSink() {
            @Override
            public void send(String json) throws IOException {
                StringBuilder event = new StringBuilder("event: message\n");
                for (String line : json.split("\r\n|\r|\n", -1)) {
                    event.append("data: ").append(line).append('\n');
                }
                output.write(event.append('\n').toString().getBytes(StandardCharsets.UTF_8));
                output.flush();
        }

            @Override
            public void comment(String text) throws IOException {
                output.write((": " + text.replace('\n', ' ').replace('\r', ' ') + "\n\n").getBytes(StandardCharsets.UTF_8));
                output.flush();
            }
        };
    }
}