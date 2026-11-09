package eu.hansolo.mcp.api;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * An icon for a tool, prompt, resource or resource template, shown by clients next to the name.
 *
 * @param src      an {@code https} or {@code http} URL, or a {@code data:} URI with base64 image data
 * @param mimeType optional MIME type override, for example {@code image/png}, or null
 * @param sizes    optional sizes, each {@code WxH} such as {@code 48x48} or {@code any}; null or empty means any size
 * @param theme    {@code "light"} (for light backgrounds), {@code "dark"} or null (any)
 */
public record Icon(String src, String mimeType, List<String> sizes, String theme) {

    private static final Pattern SIZE = Pattern.compile("any|[1-9][0-9]{0,4}x[1-9][0-9]{0,4}");

    public Icon {
        Objects.requireNonNull(src, "src");
        String scheme = scheme(src);
        if (!scheme.equals("https") && !scheme.equals("http") && !scheme.equals("data")) {
            throw new IllegalArgumentException("Icon src must be an http(s) URL or a data: URI");
        }
        sizes = sizes == null ? List.of() : List.copyOf(sizes);
        for (String size : sizes) {
            if (!SIZE.matcher(size).matches()) {
                throw new IllegalArgumentException("Icon size '" + size + "' must be WxH (for example 48x48) or any");
            }
        }
        if (theme != null && !theme.equals("light") && !theme.equals("dark")) {
            throw new IllegalArgumentException("Icon theme must be \"light\" or \"dark\"");
        }
        if (mimeType != null && mimeType.isBlank()) {
            throw new IllegalArgumentException("Icon mimeType must not be blank");
        }
    }

    /** An icon that works at any size and on any theme. */
    public static Icon of(String src) {
        return new Icon(src, null, null, null);
    }

    public static Icon of(String src, String mimeType) {
        return new Icon(src, mimeType, null, null);
    }

    public JsonObject toJson() {
        JsonObjectBuilder icon = Json.createObjectBuilder().add("src", src);
        if (mimeType != null) {
            icon.add("mimeType", mimeType);
        }
        if (!sizes.isEmpty()) {
            JsonArrayBuilder array = Json.createArrayBuilder();
            sizes.forEach(array::add);
            icon.add("sizes", array);
        }
        if (theme != null) {
            icon.add("theme", theme);
        }
        return icon.build();
    }

    /** The JSON array for a {@code icons} field. */
    public static JsonArray toJson(List<Icon> icons) {
        JsonArrayBuilder array = Json.createArrayBuilder();
        icons.forEach(icon -> array.add(icon.toJson()));
        return array.build();
    }

    private static String scheme(String src) {
        try {
            String scheme = URI.create(src).getScheme();
            return scheme == null ? "" : scheme.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Icon src is not a valid URI", e);
        }
    }
}
