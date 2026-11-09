package eu.hansolo.mcp.it;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.LinkedHashMap;
import java.util.Map;

/** A small hand-rolled MCP 2026-07-28 client over java.net.http: it sends exactly what the test says, with no helpful defaults hidden. */
final class McpClient {

    static final String VERSION = "2026-07-28";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final URI        endpoint;
    private final String     baseUrl;


    McpClient(String host, int port) {
        this.baseUrl  = "http://" + host + ":" + port;
        this.endpoint = URI.create(baseUrl + "/mcp");
    }


    record Reply(int status, String body, java.net.http.HttpHeaders headers) {

        JsonObject json() {
            return Json.createReader(new StringReader(body)).readObject();
        }

        String header(String name) {
            return headers.firstValue(name).orElse(null);
        }
    }

    String baseUrl() {
        return baseUrl;
    }

    // ---- JSON-RPC conveniences ---------------------------------------------------------------------------------------

    Reply discover(String bearer) throws IOException, InterruptedException {
        return rpc("server/discover", 1, Json.createObjectBuilder(), null, bearer, Json.createObjectBuilder().build());
    }

    Reply toolsList(String bearer) throws IOException, InterruptedException {
        return rpc("tools/list", 1, Json.createObjectBuilder(), null, bearer, Json.createObjectBuilder().build());
    }

    Reply toolsCall(String tool, JsonObject arguments, String bearer) throws IOException, InterruptedException {
        JsonObjectBuilder params = Json.createObjectBuilder().add("name", tool);
        if (arguments != null) {
            params.add("arguments", arguments);
        }
        return rpc("tools/call", 1, params, tool, bearer, Json.createObjectBuilder().build());
    }

    /** Sends a request with the standard headers and a valid _meta block. */
    Reply rpc(String method, int id, JsonObjectBuilder params, String name, String bearer, JsonObject capabilities) throws IOException, InterruptedException {
        Map<String, String> headers = standardHeaders(method, name);
        if (bearer != null) {
            headers.put("Authorization", "Bearer " + bearer);
        }
        JsonObject meta = Json.createObjectBuilder().add("io.modelcontextprotocol/protocolVersion", VERSION).add("io.modelcontextprotocol/clientInfo", Json.createObjectBuilder().add("name", "mcp-it").add("version", "0")).add("io.modelcontextprotocol/clientCapabilities", capabilities).build();
        params.add("_meta", meta);
        String body = Json.createObjectBuilder().add("jsonrpc", "2.0").add("id", id).add("method", method).add("params", params).build().toString();
        return post(headers, body);
    }

    static Map<String, String> standardHeaders(String method, String name) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json, text/event-stream");
        headers.put("MCP-Protocol-Version", VERSION);
        headers.put("Mcp-Method", method);
        if (name != null) {
            headers.put("Mcp-Name", name);
        }
        return headers;
    }

    // ---- raw HTTP --------------------------------------------------------------------------------------------------

    Reply post(Map<String, String> headers, String body) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(15)).POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        return send(request.build());
    }

    Reply get(String path, Map<String, String> headers) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(15)).GET();
        headers.forEach(request::header);
        return send(request.build());
    }

    /** Any request to a path of this server (the fixture's admin endpoint, say), with a plain-text body or none. */
    Reply request(String method, String path, String body) throws IOException, InterruptedException {
        HttpRequest.BodyPublisher publisher = body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
        HttpRequest.Builder       request   = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(15)).method(method, publisher);
        if (body != null) {
            request.header("Content-Type", "text/plain");
        }
        return send(request.build());
    }

    Reply delete(Map<String, String> headers) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(15)).DELETE();
        headers.forEach(request::header);
        return send(request.build());
    }

    private Reply send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body(), response.headers());
    }

    // ---- streamed responses ----------------------------------------------------------------------------------------

    /** One Server-Sent Event: the JSON in its data lines and the time it arrived, in milliseconds after the request was sent. */
    record Event(JsonObject json, long arrivedMs) {
    }

    /** An open response, read event by event, so a test sees when each event arrives instead of when the whole body is done. */
    static final class Events implements AutoCloseable {

        private final int            status;
        private final String         contentType;
        private final InputStream    stream;
        private final BufferedReader reader;
        private final long           startedNanos;
        private int                  comments;
        private long                 firstCommentMs = -1;


        private Events(int status, String contentType, InputStream stream, long startedNanos) {
            this.status       = status;
            this.contentType  = contentType;
            this.stream       = stream;
            this.reader       = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            this.startedNanos = startedNanos;
        }


        int status() {
            return status;
        }

        /** How many SSE comment lines (keep-alives) have been read so far. */
        int comments() {
            return comments;
        }

        /** When the first comment line arrived, in milliseconds after the request was sent; -1 if none has. */
        long firstCommentMs() {
            return firstCommentMs;
        }

        String contentType() {
            return contentType;
        }

        /** Blocks for the next event; null when the stream has ended. */
        Event next() throws IOException {
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (data.length() > 0) {
                        return new Event(Json.createReader(new StringReader(data.toString())).readObject(), (System.nanoTime() - startedNanos) / 1_000_000);
                    }
                } else if (line.startsWith(":")) {
                    comments++;
                    if (firstCommentMs < 0) {
                        firstCommentMs = (System.nanoTime() - startedNanos) / 1_000_000;
                    }
                } else if (line.startsWith("data:")) {
                    data.append(line.substring(5).stripLeading());
                }
            }
            return null;
        }

        /** Like {@link #next()}, but fails the test instead of hanging when nothing arrives (a listen stream has no end to wait for). */
        Event nextWithin(Duration timeout) throws IOException {
            CompletableFuture<Event> future = CompletableFuture.supplyAsync(() -> {
                try {
                    return next();
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
            try {
                return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                throw new AssertionError("No event within " + timeout.toSeconds() + " s");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for an event");
            } catch (ExecutionException e) {
                throw new AssertionError("Reading the stream failed", e.getCause());
            }
        }

        List<Event> all() throws IOException {
            List<Event> events = new ArrayList<>();
            for (Event event = next(); event != null; event = next()) {
                events.add(event);
            }
            return events;
        }

        /** Closing the connection is how a client cancels a streamed request. */
        @Override
        public void close() throws IOException {
            stream.close();
        }
    }

    /** Sends a tools/call with a progress token and the given Accept header, and returns as soon as the headers are in. */
    Events openToolCall(String tool, JsonObject arguments, String progressToken, String accept) throws IOException, InterruptedException {
        Map<String, String> headers = standardHeaders("tools/call", tool);
        headers.put("Accept", accept);
        JsonObjectBuilder meta = Json.createObjectBuilder().add("io.modelcontextprotocol/protocolVersion", VERSION).add("io.modelcontextprotocol/clientCapabilities", Json.createObjectBuilder());
        if (progressToken != null) {
            meta.add("progressToken", progressToken);
        }
        JsonObject params = Json.createObjectBuilder().add("name", tool).add("arguments", arguments).add("_meta", meta).build();
        String body = Json.createObjectBuilder().add("jsonrpc", "2.0").add("id", 1).add("method", "tools/call").add("params", params).build().toString();
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(60)).POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        long started = System.nanoTime();
        HttpResponse<InputStream> response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        return new Events(response.statusCode(), response.headers().firstValue("Content-Type").orElse(""), response.body(), started);
    }

    /**
     * Sends a streamed tools/call over a raw socket, waits for the first progress event, then resets the connection (SO_LINGER 0
     * makes close() send an RST, like a client that was killed). This is the disconnect a server has to detect; going through
     * HttpClient would leave it to the JDK to decide when the connection really closes.
     */
    void callAndDropAfterFirstEvent(String tool, JsonObject arguments, String progressToken) throws IOException {
        JsonObject meta = Json.createObjectBuilder().add("io.modelcontextprotocol/protocolVersion", VERSION).add("io.modelcontextprotocol/clientCapabilities", Json.createObjectBuilder()).add("progressToken", progressToken).build();
        JsonObject params = Json.createObjectBuilder().add("name", tool).add("arguments", arguments).add("_meta", meta).build();
        byte[] body = Json.createObjectBuilder().add("jsonrpc", "2.0").add("id", 1).add("method", "tools/call").add("params", params).build().toString().getBytes(StandardCharsets.UTF_8);
        StringBuilder request = new StringBuilder("POST ").append(endpoint.getPath()).append(" HTTP/1.1\r\nHost: ").append(endpoint.getHost()).append(':').append(endpoint.getPort()).append("\r\n");
        standardHeaders("tools/call", tool).forEach((name, value) -> request.append(name).append(": ").append(value).append("\r\n"));
        request.append("Content-Length: ").append(body.length).append("\r\n\r\n");
        try (Socket socket = new Socket(endpoint.getHost(), endpoint.getPort())) {
            socket.setSoLinger(true, 0);
            socket.setSoTimeout(20_000);
            socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().write(body);
            socket.getOutputStream().flush();
            StringBuilder received = new StringBuilder();
            byte[] buffer = new byte[1024];
            while (!received.toString().contains("notifications/progress")) {
                int read = socket.getInputStream().read(buffer);
                if (read < 0) {
                    throw new IOException("The server closed the connection before the first progress event: " + received);
                }
                received.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
            }
        }
    }

    /** Opens a subscriptions/listen stream. The id of the request is the subscription id. */
    Events openListen(int id, JsonObject notificationFilter, String bearer, String accept) throws IOException, InterruptedException {
        return openListen(id, notificationFilter, bearer, accept, Json.createObjectBuilder().build());
    }

    /** Like the above, with the capabilities the client declares (for example the Tasks extension). */
    Events openListen(int id, JsonObject notificationFilter, String bearer, String accept, JsonObject capabilities) throws IOException, InterruptedException {
        Map<String, String> headers = standardHeaders("subscriptions/listen", null);
        headers.put("Accept", accept);
        if (bearer != null) {
            headers.put("Authorization", "Bearer " + bearer);
        }
        JsonObject meta = Json.createObjectBuilder().add("io.modelcontextprotocol/protocolVersion", VERSION).add("io.modelcontextprotocol/clientCapabilities", capabilities).build();
        JsonObject params = Json.createObjectBuilder().add("notifications", notificationFilter).add("_meta", meta).build();
        String body = Json.createObjectBuilder().add("jsonrpc", "2.0").add("id", id).add("method", "subscriptions/listen").add("params", params).build().toString();
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(30)).POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        long started = System.nanoTime();
        HttpResponse<InputStream> response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        return new Events(response.statusCode(), response.headers().firstValue("Content-Type").orElse(""), response.body(), started);
    }
}
