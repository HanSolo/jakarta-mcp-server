package eu.hansolo.mcp.server;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/** Reads settings from a system property, then from an environment variable (the property name upper-cased, with '.' and '-' replaced by '_'). */
public final class Settings {

    private static final Logger LOG = Logger.getLogger(Settings.class.getName());


    private Settings() {
    }


    /** The raw value, or null if unset. */
    public static String get(String property) {
        String value = System.getProperty(property);
        if (value == null) {
            value = System.getenv(property.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_'));
        }
        return value == null || value.isBlank() ? null : value.trim();
    }

    public static int getInt(String property, int fallback) {
        String value = get(property);
        try {
            return value == null ? fallback : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            LOG.warning("Ignoring invalid integer setting " + property + "='" + value + "', using " + fallback);
            return fallback;
        }
    }

    public static long getLong(String property, long fallback) {
        String value = get(property);
        try {
            return value == null ? fallback : Long.parseLong(value);
        } catch (NumberFormatException e) {
            LOG.warning("Ignoring invalid number setting " + property + "='" + value + "', using " + fallback);
            return fallback;
        }
    }

    public static boolean getBoolean(String property, boolean fallback) {
        String value = get(property);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    /** A comma-separated list with blanks removed; empty if unset. */
    public static List<String> getList(String property) {
        String value = get(property);
        return value == null ? List.of() : Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
