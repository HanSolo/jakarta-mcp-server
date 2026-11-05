package eu.hansolo.mcp.server;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UriTemplateTest {

    @Test
    void matchesASingleVariableAndDecodesIt() {
        UriTemplate template = UriTemplate.compile("greeting://{name}");
        assertEquals(Map.of("name", "Ann Lee"), template.match("greeting://Ann%20Lee").orElseThrow());
        assertEquals(Map.of("name", "a+b"), template.match("greeting://a+b").orElseThrow());
    }

    @Test
    void plainVariablesStopAtSlashQueryAndFragment() {
        UriTemplate template = UriTemplate.compile("greeting://{name}");
        assertTrue(template.match("greeting://a/b").isEmpty());
        assertTrue(template.match("greeting://a?b").isEmpty());
        assertTrue(template.match("greeting://a#b").isEmpty());
        assertTrue(template.match("greeting://").isEmpty());
    }

    @Test
    void reservedExpansionMatchesSlashes() {
        UriTemplate template = UriTemplate.compile("file:///docs/{+path}");
        assertEquals(Map.of("path", "a/b/c.txt"), template.match("file:///docs/a/b/c.txt").orElseThrow());
    }

    @Test
    void severalVariablesAreSeparatedByLiterals() {
        UriTemplate template = UriTemplate.compile("repo://{owner}/{name}");
        assertEquals(Map.of("owner", "o", "name", "n"), template.match("repo://o/n").orElseThrow());
        assertEquals(java.util.List.of("owner", "name"), template.variables());
    }

    @Test
    void literalsAreNotInterpretedAsRegex() {
        UriTemplate template = UriTemplate.compile("x://a.b/{id}");
        assertTrue(template.match("x://aXb/1").isEmpty());
        assertEquals(Map.of("id", "1"), template.match("x://a.b/1").orElseThrow());
    }

    @Test
    void rejectsTemplatesThatCannotBeMatchedUnambiguously() {
        assertThrows(IllegalArgumentException.class, () -> UriTemplate.compile("{name}"));
        assertThrows(IllegalArgumentException.class, () -> UriTemplate.compile("x://{a}{b}"));
        assertThrows(IllegalArgumentException.class, () -> UriTemplate.compile("x://{a}/{a}"));
        assertThrows(IllegalArgumentException.class, () -> UriTemplate.compile("x://{a"));
        assertThrows(IllegalArgumentException.class, () -> UriTemplate.compile("x://{}"));
    }
}
