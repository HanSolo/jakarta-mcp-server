package eu.hansolo.mcp.it;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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

    Reply delete(Map<String, String> headers) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(15)).DELETE();
        headers.forEach(request::header);
        return send(request.build());
    }

    private Reply send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body(), response.headers());
    }
}
