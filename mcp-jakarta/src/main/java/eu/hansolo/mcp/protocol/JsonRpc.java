package eu.hansolo.mcp.protocol;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonValue;

/** Builders for JSON-RPC 2.0 response envelopes. */
public final class JsonRpc {

    private JsonRpc() {
    }


    public static JsonObject result(JsonValue id, JsonObject result) {
        return Json.createObjectBuilder().add("jsonrpc", "2.0").add("id", id).add("result", result).build();
    }

    /** Builds an error response. A null id is omitted, which is what the spec requires when the request id could not be read. */
    public static JsonObject error(JsonValue id, int code, String message, JsonValue data) {
        JsonObjectBuilder error = Json.createObjectBuilder().add("code", code).add("message", message);
        if (data != null) {
            error.add("data", data);
        }
        JsonObjectBuilder response = Json.createObjectBuilder().add("jsonrpc", "2.0");
        if (id != null) {
            response.add("id", id);
        }
        return response.add("error", error).build();
    }
}
