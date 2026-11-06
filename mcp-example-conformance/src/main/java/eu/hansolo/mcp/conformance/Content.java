package eu.hansolo.mcp.conformance;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import eu.hansolo.mcp.api.ToolResult;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Content blocks and sample media for the fixtures. */
final class Content {

    /** A valid 1x1 PNG. */
    static final String PNG_BASE64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";

    private Content() {
    }


    static JsonObject text(String text) {
        return Json.createObjectBuilder().add("type", "text").add("text", text).build();
    }

    static JsonObject image() {
        return Json.createObjectBuilder().add("type", "image").add("data", PNG_BASE64).add("mimeType", "image/png").build();
    }

    static JsonObject audio() {
        return Json.createObjectBuilder().add("type", "audio").add("data", Base64.getEncoder().encodeToString(wav())).add("mimeType", "audio/wav").build();
    }

    static JsonObject resource(String uri, String mimeType, String text) {
        JsonObject resource = Json.createObjectBuilder().add("uri", uri).add("mimeType", mimeType).add("text", text).build();
        return Json.createObjectBuilder().add("type", "resource").add("resource", resource).build();
    }

    static ToolResult blocks(JsonObject... blocks) {
        var content = Json.createArrayBuilder();
        for (JsonObject block : blocks) {
            content.add(block);
        }
        return new ToolResult.Complete(content.build(), null, false);
    }

    /** A 100 ms silent 8 kHz, 8 bit mono WAV file, built from its header fields. */
    static byte[] wav() {
        int samples = 800;
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + samples).put("WAVE".getBytes(StandardCharsets.US_ASCII));
        header.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1).putInt(8000).putInt(8000).putShort((short) 1).putShort((short) 8);
        header.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(samples);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(header.array());
        for (int i = 0; i < samples; i++) {
            out.write(128);
        }
        return out.toByteArray();
    }
}
