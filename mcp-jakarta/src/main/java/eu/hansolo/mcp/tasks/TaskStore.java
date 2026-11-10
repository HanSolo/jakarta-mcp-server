package eu.hansolo.mcp.tasks;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Where task records live. Every instance of a clustered server must see the same store, and a record must be visible to
 * {@link #find} as soon as {@link #create} returns: the specification requires the task to exist before the client hears of it.
 *
 * <p>The server never changes a record in place. It reads it, builds the next version with {@link TaskRecord#edit} and calls
 * {@link #replace}; the store must accept the new version only if the stored one is still the one the change was based on.
 * Implement the store as a CDI bean (any bean-defining scope) to replace the default {@link InMemoryTaskStore}.
 *
 * <p>The store knows nothing about owners: the server checks the owner after {@link #find}.
 */
public interface TaskStore {

    /**
     * False if this store is on the classpath but not configured, so the server must not use it. Lets a store module sit in an
     * application that does not use it. The server uses the one available store; two available stores are an error, none means the
     * in-memory store.
     */
    default boolean available() {
        return true;
    }

    /**
     * Stores a new task.
     *
     * @throws IllegalStateException if a task with this id exists
     */
    void create(TaskRecord task);

    Optional<TaskRecord> find(String id);

    /**
     * Looks up several tasks at once. A stream that watches many tasks calls this once per round instead of {@link #find} once per task, so
     * a store behind a network should override it with a single query. The default calls {@link #find} for each id.
     *
     * @param ids the task ids; duplicates and {@code null} are ignored
     * @return the tasks that exist, by id; unknown ids are absent
     */
    default Map<String, TaskRecord> findAll(Collection<String> ids) {
        Map<String, TaskRecord> found = new LinkedHashMap<>();
        for (String id : ids) {
            if (id != null && !found.containsKey(id)) {
                find(id).ifPresent(record -> found.put(id, record));
            }
        }
        return found;
    }

    /**
     * Compare and set.
     *
     * @param current the version the change is based on
     * @param next    the new version; {@code next.version() == current.version() + 1}
     * @return true if it was stored; false if the stored record is gone or no longer has the version of {@code current}
     */
    boolean replace(TaskRecord current, TaskRecord next);

    /** Ids of non-terminal tasks whose lease ended before {@code now}: their worker is gone. At most {@code limit}. */
    List<String> leaseExpired(Instant now, int limit);

    /** Ids of tasks whose {@code expiresAt} is not after {@code now}. At most {@code limit}. */
    List<String> expired(Instant now, int limit);

    /** Removes a task; a task that does not exist is not an error. */
    void delete(String id);
}
