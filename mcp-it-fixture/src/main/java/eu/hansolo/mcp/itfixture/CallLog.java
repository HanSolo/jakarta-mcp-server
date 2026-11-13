package eu.hansolo.mcp.itfixture;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** What the interceptor and the tools saw, in order. Read through {@code GET /admin/calls}. */
@ApplicationScoped
public class CallLog {

    private final List<String>         entries = new ArrayList<>();
    private final Map<String, Integer> counts  = new HashMap<>();


    public synchronized void record(String entry) {
        entries.add(entry);
    }

    /** Counts one more use of the key and returns the new total. */
    public synchronized int count(String key) {
        return counts.merge(key, 1, Integer::sum);
    }

    public synchronized List<String> all() {
        return List.copyOf(entries);
    }

    public synchronized void clear() {
        entries.clear();
        counts.clear();
    }
}
