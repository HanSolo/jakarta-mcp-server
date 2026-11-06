package eu.hansolo.mcp.server;

import jakarta.json.Json;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonValue;
import eu.hansolo.mcp.api.ProgressReporter;
import eu.hansolo.mcp.api.RequestCancelledException;

import java.io.IOException;

/**
 * Writes {@code notifications/progress} events into the response stream of one request. Synchronized, because a tool may report
 * from several threads. Once a write fails the client is gone: the request counts as cancelled and every later report throws.
 */
final class SseProgress implements ProgressReporter {

    private static final int MAX_MESSAGE = 1000;

    private final McpHttpResponse.EventSink sink;
    private final JsonValue                 token;
    private double                          last      = Double.NEGATIVE_INFINITY;
    private boolean                         cancelled;


    SseProgress(McpHttpResponse.EventSink sink, JsonValue token) {
        this.sink  = sink;
        this.token = token;
    }


    @Override
    public synchronized void report(double progress, Double total, String message) {
        if (cancelled) {
            throw new RequestCancelledException("The client closed the stream", null);
        }
        if (Double.isNaN(progress) || Double.isInfinite(progress)) {
            throw new IllegalArgumentException("progress must be a finite number");
        }
        if (progress <= last) {
            throw new IllegalArgumentException("progress must increase with every report: " + progress + " after " + last);
        }
        if (total != null && (Double.isNaN(total) || Double.isInfinite(total))) {
            throw new IllegalArgumentException("total must be a finite number");
        }
        JsonObjectBuilder params = Json.createObjectBuilder().add("progressToken", token).add("progress", progress);
        if (total != null) {
            params.add("total", total);
        }
        if (message != null) {
            params.add("message", message.length() > MAX_MESSAGE ? message.substring(0, MAX_MESSAGE) : message);
        }
        try {
            sink.send(Json.createObjectBuilder().add("jsonrpc", "2.0").add("method", "notifications/progress").add("params", params).build().toString());
        } catch (IOException e) {
            cancelled = true;
            throw new RequestCancelledException("The client closed the stream", e);
        }
        last = progress;
    }

    @Override
    public boolean isActive() {
        return true;
    }

    synchronized boolean isCancelled() {
        return cancelled;
    }
}
