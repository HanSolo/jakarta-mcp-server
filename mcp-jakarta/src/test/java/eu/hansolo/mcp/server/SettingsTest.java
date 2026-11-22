package eu.hansolo.mcp.server;

import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@code Settings} reads a system property first, then the environment, then MicroProfile Config. The test class path has a stand-in
 * for the MicroProfile Config API (package {@code org.eclipse.microprofile.config} under {@code src/test}), which is how the
 * by-name lookup is exercised without the real implementation.
 */
class SettingsTest {

    private static final String KEY = "mcp.settings-test.value";


    @AfterEach
    void clean() {
        ConfigProvider.VALUES.clear();
        System.clearProperty(KEY);
    }


    @Test
    void anUnsetSettingIsNull() {
        assertNull(Settings.get(KEY));
    }

    @Test
    void microProfileConfigIsReadWhenNothingElseHasTheSetting() {
        ConfigProvider.VALUES.put(KEY, "  from-config ");
        assertEquals("from-config", Settings.get(KEY));
    }

    @Test
    void aSystemPropertyWinsOverMicroProfileConfig() {
        ConfigProvider.VALUES.put(KEY, "from-config");
        System.setProperty(KEY, "from-property");
        assertEquals("from-property", Settings.get(KEY));
    }

    @Test
    void aBlankValueCountsAsUnset() {
        ConfigProvider.VALUES.put(KEY, "   ");
        assertNull(Settings.get(KEY));
    }

    @Test
    void theTypedGettersReadMicroProfileConfigToo() {
        ConfigProvider.VALUES.put(KEY, "42");
        assertEquals(42, Settings.getInt(KEY, 7));
        assertEquals(42L, Settings.getLong(KEY, 7L));
        ConfigProvider.VALUES.put(KEY, "true");
        assertEquals(true, Settings.getBoolean(KEY, false));
        ConfigProvider.VALUES.put(KEY, "a, b ,,c");
        assertEquals(List.of("a", "b", "c"), Settings.getList(KEY));
    }

    @Test
    void aNumberThatDoesNotParseFallsBack() {
        ConfigProvider.VALUES.put(KEY, "many");
        assertEquals(7, Settings.getInt(KEY, 7));
    }
}
