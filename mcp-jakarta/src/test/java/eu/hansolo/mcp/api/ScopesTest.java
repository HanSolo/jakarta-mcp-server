package eu.hansolo.mcp.api;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScopesTest {

    @Test
    void exactScopeSatisfies() {
        assertTrue(Scopes.satisfies(Set.of("files:read"), "files:read"));
    }

    @Test
    void broaderScopeImpliesNarrowerOnes() {
        assertTrue(Scopes.satisfies(Set.of("files"), "files:read"));
        assertTrue(Scopes.satisfies(Set.of("files"), "files:read:archive"));
    }

    @Test
    void narrowerScopeDoesNotImplyBroaderOnes() {
        assertFalse(Scopes.satisfies(Set.of("files:read"), "files"));
        assertFalse(Scopes.satisfies(Set.of("files:read"), "files:write"));
    }

    @Test
    void prefixWithoutColonBoundaryDoesNotMatch() {
        assertFalse(Scopes.satisfies(Set.of("file"), "files"));
        assertFalse(Scopes.satisfies(Set.of("files"), "filesystem:read"));
    }

    @Test
    void satisfiesAllNeedsEveryScopeAndEmptyRequirementAlwaysPasses() {
        assertTrue(Scopes.satisfiesAll(Set.of(), Set.of()));
        assertTrue(Scopes.satisfiesAll(Set.of("a", "b"), Set.of("a", "b")));
        assertFalse(Scopes.satisfiesAll(Set.of("a"), Set.of("a", "b")));
    }
}
