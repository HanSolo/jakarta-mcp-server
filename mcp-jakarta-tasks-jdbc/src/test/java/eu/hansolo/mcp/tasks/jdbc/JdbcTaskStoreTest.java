package eu.hansolo.mcp.tasks.jdbc;

import eu.hansolo.mcp.tasks.TaskRecord;
import eu.hansolo.mcp.tasks.TaskStatus;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The JDBC store against an in-memory H2 database. */
class JdbcTaskStoreTest {

    private static final Instant T0 = Instant.parse("2026-11-09T12:00:00Z");


    @Test
    void aTaskSurvivesAStoreRoundTripWithAllItsFields() {
        JdbcTaskStore store = store();
        TaskRecord created = TaskRecord.create("t1", "alice", "report", T0, 3_600_000L, 2_000L, "worker-a", T0.plusSeconds(30));
        store.create(created);
        assertEquals(created, store.find("t1").orElseThrow());

        JsonObject requests = Json.createObjectBuilder().add("confirm", Json.createObjectBuilder().add("mode", "form")).build();
        JsonObject result = Json.createObjectBuilder().add("resultType", "complete").add("isError", false).build();
        TaskRecord next = created.edit(T0.plusSeconds(5), edit -> edit.status(TaskStatus.COMPLETED).statusMessage("done é中").result(result).inputRequests(requests).cancelRequested(true));
        assertTrue(store.replace(created, next));
        TaskRecord read = store.find("t1").orElseThrow();
        assertEquals(next, read);
        assertEquals("done é中", read.statusMessage());
        assertEquals(result, read.result());
        assertEquals(requests, read.inputRequests());
        assertTrue(read.cancelRequested());
    }

    @Test
    void aTaskWithoutExpiryAndLeaseKeepsItsNulls() {
        JdbcTaskStore store = store();
        TaskRecord created = new TaskRecord("t2", "bob", "x", TaskStatus.WORKING, null, T0, T0, null, null, 500L, null, null, null, null, false, null, null, 0L);
        store.create(created);
        TaskRecord read = store.find("t2").orElseThrow();
        assertEquals(created, read);
        assertEquals(null, read.ttlMs());
        assertEquals(null, read.expiresAt());
        assertEquals(null, read.leaseUntil());
    }

    @Test
    void anUnknownTaskIsEmptyAndADuplicateIdIsRefused() {
        JdbcTaskStore store = store();
        assertFalse(store.find("nope").isPresent());
        TaskRecord created = TaskRecord.create("dup", "alice", "x", T0, 1000L, 100L, "w", T0.plusSeconds(30));
        store.create(created);
        assertThrows(IllegalStateException.class, () -> store.create(created));
    }

    @Test
    void replaceIsCompareAndSet() {
        JdbcTaskStore store = store();
        TaskRecord created = TaskRecord.create("cas", "alice", "x", T0, 1000L, 100L, "w", T0.plusSeconds(30));
        store.create(created);
        TaskRecord first = created.edit(T0.plusSeconds(1), edit -> edit.statusMessage("one"));
        TaskRecord rival = created.edit(T0.plusSeconds(1), edit -> edit.statusMessage("rival"));
        assertTrue(store.replace(created, first));
        assertFalse(store.replace(created, rival));
        assertEquals("one", store.find("cas").orElseThrow().statusMessage());
        store.delete("cas");
        assertFalse(store.replace(first, first.edit(T0.plusSeconds(2), edit -> edit.statusMessage("late"))));
        assertThrows(IllegalArgumentException.class, () -> store.replace(created, created));
    }

    @Test
    void leaseAndExpiryQueriesFindTheRightTasks() {
        JdbcTaskStore store = store();
        store.create(TaskRecord.create("live", "a", "x", T0, 10_000L, 100L, "w", T0.plusSeconds(60)));
        store.create(TaskRecord.create("lost", "a", "x", T0, 10_000L, 100L, "w", T0.plusSeconds(5)));
        TaskRecord done = TaskRecord.create("done", "a", "x", T0, 3_000L, 100L, "w", T0.plusSeconds(5));
        store.create(done);
        store.replace(done, done.edit(T0.plusSeconds(1), edit -> edit.status(TaskStatus.COMPLETED)));
        store.create(new TaskRecord("forever", "a", "x", TaskStatus.WORKING, null, T0, T0, null, null, 100L, null, null, null, null, false, "w", T0.plusSeconds(5), 0L));

        Instant later = T0.plusSeconds(20);
        assertEquals(List.of("forever", "lost").stream().sorted().toList(), store.leaseExpired(later, 10).stream().sorted().toList());
        assertEquals(1, store.leaseExpired(later, 1).size());
        assertEquals(List.of("done"), store.expired(T0.plusSeconds(4), 10));
        assertEquals(List.of("done", "live", "lost").stream().sorted().toList(), store.expired(T0.plusSeconds(11), 10).stream().sorted().toList());
        store.delete("lost");
        store.delete("lost");
        assertFalse(store.find("lost").isPresent());
    }

    @Test
    void theTableNameMustBeAPlainIdentifier() {
        assertThrows(IllegalStateException.class, () -> new JdbcTaskStore(dataSource(), "tasks; DROP TABLE x", false));
        assertThrows(IllegalStateException.class, () -> JdbcTaskStore.ddl("1abc", "CLOB"));
        assertTrue(JdbcTaskStore.ddl("my_tasks", "TEXT").get(0).contains("my_tasks"));
    }


    static DataSource dataSource() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        return ds;
    }

    private static JdbcTaskStore store() {
        return new JdbcTaskStore(dataSource(), "mcp_tasks", true);
    }
}
