package eu.hansolo.mcp.tasks;

import jakarta.enterprise.inject.Vetoed;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The default store: a map in the memory of one JVM. It is correct for a single instance, but it is not durable in the sense of the
 * specification: tasks are lost when the server stops, and another instance cannot see them. Use a shared store (see the
 * {@code mcp-jakarta-tasks-jdbc} module) for anything that runs on more than one instance or must survive a restart.
 */
@Vetoed
public class InMemoryTaskStore implements TaskStore {

    private final Map<String, TaskRecord> tasks = new ConcurrentHashMap<>();


    @Override
    public void create(TaskRecord task) {
        if (tasks.putIfAbsent(task.id(), task) != null) {
            throw new IllegalStateException("Task " + task.id() + " exists");
        }
    }

    @Override
    public Optional<TaskRecord> find(String id) {
        return Optional.ofNullable(tasks.get(id));
    }

    @Override
    public boolean replace(TaskRecord current, TaskRecord next) {
        if (next.version() != current.version() + 1 || !next.id().equals(current.id())) {
            throw new IllegalArgumentException("next must be the successor of current");
        }
        AtomicBoolean stored = new AtomicBoolean();
        tasks.computeIfPresent(current.id(), (id, existing) -> {
            if (existing.version() == current.version()) {
                stored.set(true);
                return next;
            }
            return existing;
        });
        return stored.get();
    }

    @Override
    public List<String> leaseExpired(Instant now, int limit) {
        return tasks.values().stream().filter(t -> !t.status().isTerminal() && t.leaseUntil() != null && t.leaseUntil().isBefore(now)).limit(limit).map(TaskRecord::id).toList();
    }

    @Override
    public List<String> pollDue(Instant now, int limit) {
        return tasks.values().stream().filter(t -> !t.status().isTerminal() && t.nextPollAt() != null && !t.nextPollAt().isAfter(now)).sorted(java.util.Comparator.comparing(TaskRecord::nextPollAt)).limit(limit).map(TaskRecord::id).toList();
    }

    @Override
    public List<String> expired(Instant now, int limit) {
        return tasks.values().stream().filter(t -> t.expiresAt() != null && !t.expiresAt().isAfter(now)).limit(limit).map(TaskRecord::id).toList();
    }

    @Override
    public void delete(String id) {
        tasks.remove(id);
    }

    /** How many tasks are stored (for tests and diagnostics). */
    public int size() {
        return tasks.size();
    }
}
