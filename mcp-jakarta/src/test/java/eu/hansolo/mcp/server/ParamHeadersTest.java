package eu.hansolo.mcp.server;

import eu.hansolo.mcp.protocol.ErrorCodes;
import eu.hansolo.mcp.protocol.McpProtocolException;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParamHeadersTest {

    private static JsonObject prop(String type, String header) {
        JsonObjectBuilder b = Json.createObjectBuilder().add("type", type);
        if (header != null) {
            b.add("x-mcp-header", header);
        }
        return b.build();
    }

    private static JsonObject schema(String key, JsonObject property) {
        return Json.createObjectBuilder().add("type", "object").add("properties", Json.createObjectBuilder().add(key, property)).build();
    }

    private static Map<String, String> headers(String... pairs) {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (int i = 0; i < pairs.length; i += 2) {
            headers.put(pairs[i], pairs[i + 1]);
        }
        return headers;
    }

    private static void check(JsonObject schema, JsonObject arguments, Map<String, String> headers) {
        ParamHeaders.check(ParamHeaders.declared(schema), arguments, headers::get);
    }

    private static void rejects(JsonObject schema, JsonObject arguments, Map<String, String> headers) {
        McpProtocolException e = assertThrows(McpProtocolException.class, () -> check(schema, arguments, headers));
        assertEquals(400, e.httpStatus());
        assertEquals(ErrorCodes.HEADER_MISMATCH, e.code());
    }

    // ---- schema ----------------------------------------------------------------------------------------------------

    @Test
    void findsAnnotatedPropertiesIncludingNestedOnes() {
        JsonObject inner = Json.createObjectBuilder().add("type", "object").add("properties", Json.createObjectBuilder().add("zone", prop("string", "Zone"))).build();
        JsonObject root  = Json.createObjectBuilder().add("type", "object").add("properties", Json.createObjectBuilder().add("region", prop("string", "Region")).add("n", prop("integer", null)).add("where", inner)).build();
        List<ParamHeaders.Param> found = ParamHeaders.declared(root);
        assertEquals(2, found.size());
        assertEquals("Mcp-Param-Region", found.get(0).headerName());
        assertEquals(List.of("where", "zone"), found.get(1).path());
    }

    @Test
    void rejectsInvalidDefinitions() {
        assertThrows(IllegalArgumentException.class, () -> ParamHeaders.declared(schema("a", prop("string", ""))));
        assertThrows(IllegalArgumentException.class, () -> ParamHeaders.declared(schema("a", prop("string", "Bad Name"))));
        assertThrows(IllegalArgumentException.class, () -> ParamHeaders.declared(schema("a", prop("string", "Café"))));
        assertThrows(IllegalArgumentException.class, () -> ParamHeaders.declared(schema("a", prop("number", "A"))));
        assertThrows(IllegalArgumentException.class, () -> ParamHeaders.declared(schema("a", Json.createObjectBuilder().add("x-mcp-header", "A").build())));
        JsonObject twice = Json.createObjectBuilder().add("type", "object").add("properties", Json.createObjectBuilder().add("a", prop("string", "Region")).add("b", prop("string", "REGION"))).build();
        assertThrows(IllegalArgumentException.class, () -> ParamHeaders.declared(twice));
        assertThrows(IllegalArgumentException.class, () -> ParamHeaders.declared(Json.createObjectBuilder().add("type", "object").add("x-mcp-header", "A").build()));
    }

    @Test
    void rejectsTheAnnotationWhereNoPropertiesChainReachesIt() {
        JsonObject items = Json.createObjectBuilder().add("type", "array").add("items", prop("string", "A")).build();
        assertThrows(IllegalArgumentException.class, () -> ParamHeaders.declared(schema("list", items)));
        JsonObject composed = Json.createObjectBuilder().add("anyOf", Json.createArrayBuilder().add(prop("string", "A"))).build();
        assertThrows(IllegalArgumentException.class, () -> ParamHeaders.declared(schema("x", composed)));
    }

    @Test
    void ignoresTheAnnotationInsideData() {
        JsonObject property = Json.createObjectBuilder().add("type", "string").add("default", "x-mcp-header").add("examples", Json.createArrayBuilder().add(Json.createObjectBuilder().add("x-mcp-header", "A"))).build();
        assertTrue(ParamHeaders.declared(schema("a", property)).isEmpty());
    }

    // ---- request ---------------------------------------------------------------------------------------------------

    @Test
    void acceptsAMatchingStringHeaderAndIsCaseInsensitiveOnTheName() {
        JsonObject s = schema("region", prop("string", "Region"));
        check(s, Json.createObjectBuilder().add("region", "us-west1").build(), headers("mcp-param-region", "us-west1"));
    }

    @Test
    void rejectsMissingAndDifferingHeaders() {
        JsonObject s = schema("region", prop("string", "Region"));
        JsonObject args = Json.createObjectBuilder().add("region", "us-west1").build();
        rejects(s, args, headers());
        rejects(s, args, headers("Mcp-Param-Region", "us-west2"));
        rejects(s, args, headers("Mcp-Param-Region", "us-west1".toUpperCase()));
    }

    @Test
    void anAbsentOrNullArgumentNeedsNoHeader() {
        JsonObject s = schema("region", prop("string", "Region"));
        check(s, JsonObject.EMPTY_JSON_OBJECT, headers());
        check(s, Json.createObjectBuilder().addNull("region").build(), headers());
    }

    @Test
    void decodesTheBase64SentinelAndRejectsBrokenOnes() {
        JsonObject s = schema("name", prop("string", "Name"));
        String text = "Zürich";
        String encoded = "=?base64?" + Base64.getEncoder().encodeToString(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "?=";
        check(s, Json.createObjectBuilder().add("name", text).build(), headers("Mcp-Param-Name", encoded));
        rejects(s, Json.createObjectBuilder().add("name", text).build(), headers("Mcp-Param-Name", "=?base64?!!!?="));
        rejects(s, Json.createObjectBuilder().add("name", text).build(), headers("Mcp-Param-Name", text));
        rejects(s, JsonObject.EMPTY_JSON_OBJECT, headers("Mcp-Param-Name", "=?base64?!!!?="));
    }

    @Test
    void comparesIntegersNumerically() {
        JsonObject s = schema("n", prop("integer", "N"));
        check(s, Json.createObjectBuilder().add("n", 42).build(), headers("Mcp-Param-N", "42"));
        check(s, Json.createObjectBuilder().add("n", new java.math.BigDecimal("42.0")).build(), headers("Mcp-Param-N", "42"));
        rejects(s, Json.createObjectBuilder().add("n", 42).build(), headers("Mcp-Param-N", "43"));
        rejects(s, Json.createObjectBuilder().add("n", 42).build(), headers("Mcp-Param-N", "42.0"));
        rejects(s, Json.createObjectBuilder().add("n", 42).build(), headers("Mcp-Param-N", "abc"));
    }

    @Test
    void booleansAreLowercase() {
        JsonObject s = schema("on", prop("boolean", "On"));
        check(s, Json.createObjectBuilder().add("on", true).build(), headers("Mcp-Param-On", "true"));
        rejects(s, Json.createObjectBuilder().add("on", true).build(), headers("Mcp-Param-On", "True"));
        rejects(s, Json.createObjectBuilder().add("on", false).build(), headers("Mcp-Param-On", "true"));
    }

    @Test
    void readsNestedValuesAndIgnoresUnknownHeaders() {
        JsonObject inner = Json.createObjectBuilder().add("type", "object").add("properties", Json.createObjectBuilder().add("zone", prop("string", "Zone"))).build();
        JsonObject s = schema("where", inner);
        JsonObject args = Json.createObjectBuilder().add("where", Json.createObjectBuilder().add("zone", "a")).build();
        check(s, args, headers("Mcp-Param-Zone", "a", "Mcp-Param-Other", "whatever"));
        rejects(s, args, headers("Mcp-Param-Zone", "b"));
    }
}
