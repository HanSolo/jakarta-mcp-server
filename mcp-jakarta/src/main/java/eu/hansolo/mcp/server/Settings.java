package eu.hansolo.mcp.server;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reads settings from a system property, then from an environment variable (the property name upper-cased, with '.' and '-' replaced by
 * '_'), then, if the application has MicroProfile Config, from its configuration (for example {@code microprofile-config.properties},
 * or a source the server provides). MicroProfile Config is optional: it is found by name when the API is on the class path, and
 * without it nothing changes.
 */
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
        if (value == null || value.isBlank()) {
            value = MicroProfile.get(property);
        }
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** The setting as an integer, or the fallback if it is unset or not a number (a warning is logged for the latter). */
    public static int getInt(String property, int fallback) {
        String value = get(property);
        try {
            return value == null ? fallback : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            LOG.warning("Ignoring invalid integer setting " + property + "='" + value + "', using " + fallback);
            return fallback;
        }
    }

    /** The setting as a long, or the fallback if it is unset or not a number (a warning is logged for the latter). */
    public static long getLong(String property, long fallback) {
        String value = get(property);
        try {
            return value == null ? fallback : Long.parseLong(value);
        } catch (NumberFormatException e) {
            LOG.warning("Ignoring invalid number setting " + property + "='" + value + "', using " + fallback);
            return fallback;
        }
    }

    /** The setting as a boolean, or the fallback if it is unset. Anything but {@code true} (ignoring case) is false. */
    public static boolean getBoolean(String property, boolean fallback) {
        String value = get(property);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    /** A comma-separated list with blanks removed; empty if unset. */
    public static List<String> getList(String property) {
        String value = get(property);
        return value == null ? List.of() : Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }


    /** The configuration of MicroProfile Config, read by reflection so that the API stays optional. */
    private static final class MicroProfile {

        private static volatile boolean   resolved;
        private static volatile Method    getConfig;
        private static volatile Method    getOptionalValue;


        static String get(String property) {
            if (!resolved) {
                resolve();
            }
            if (getConfig == null) {
                return null;
            }
            try {
                Object config   = getConfig.invoke(null);
                Object optional = getOptionalValue.invoke(config, property, String.class);
                return optional instanceof Optional<?> found && found.isPresent() ? String.valueOf(found.get()) : null;
            } catch (ReflectiveOperationException | RuntimeException e) {
                LOG.log(Level.FINE, "MicroProfile Config could not answer for " + property, e);   // no provider, or a value that does not convert
                return null;
            }
        }

        private static synchronized void resolve() {
            if (resolved) {
                return;
            }
            try {
                ClassLoader loader = Settings.class.getClassLoader();
                Class<?> provider  = Class.forName("org.eclipse.microprofile.config.ConfigProvider", true, loader);
                Class<?> config    = Class.forName("org.eclipse.microprofile.config.Config", true, loader);
                getConfig          = provider.getMethod("getConfig");
                getOptionalValue   = config.getMethod("getOptionalValue", String.class, Class.class);
            } catch (ReflectiveOperationException | LinkageError e) {
                getConfig = null;   // no MicroProfile Config here
            }
            resolved = true;
        }
    }
}
