package eu.hansolo.mcp.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Suggestions for one argument. The protocol allows at most 100 values per response: the server cuts longer lists and sets
 * {@code hasMore}, so return the best matches first.
 *
 * @param values  suggestions, most relevant first
 * @param total   the number of matches that exist, or null if unknown
 * @param hasMore whether more matches exist than are returned, or null to let the server work it out
 */
public record CompletionResult(List<String> values, Long total, Boolean hasMore) {

    public CompletionResult {
        values = List.copyOf(Objects.requireNonNull(values, "values"));
        if (total != null && total < 0) {
            throw new IllegalArgumentException("total must not be negative");
        }
    }


    public static CompletionResult of(List<String> values) {
        return new CompletionResult(values, null, null);
    }

    public static CompletionResult of(String... values) {
        return new CompletionResult(List.of(values), null, null);
    }

    public static CompletionResult empty() {
        return new CompletionResult(List.of(), 0L, false);
    }

    /** The candidates that start with what the user has typed so far (ignoring case), in the order given, with the match count as total. */
    public static CompletionResult matching(Collection<String> candidates, String typed) {
        String prefix = typed == null ? "" : typed.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String candidate : candidates) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                matches.add(candidate);
            }
        }
        return new CompletionResult(matches, (long) matches.size(), null);
    }
}
