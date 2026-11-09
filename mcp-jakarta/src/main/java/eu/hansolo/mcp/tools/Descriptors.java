package eu.hansolo.mcp.tools;

import jakarta.json.JsonObject;
import eu.hansolo.mcp.api.Icon;
import eu.hansolo.mcp.api.Meta;

import java.util.Arrays;
import java.util.List;

/** Turns the {@code icons} and {@code meta} attributes of the annotations into what the handlers return. */
final class Descriptors {

    private Descriptors() {
    }


    /** One icon per URL; use a handler class for icons that need a MIME type, sizes or a theme. */
    static List<Icon> icons(String[] sources, String where) {
        try {
            return Arrays.stream(sources).map(Icon::of).toList();
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(where + ": " + e.getMessage(), e);
        }
    }

    static JsonObject meta(String json, String where) {
        try {
            return Meta.parse(json, where);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }
}
