package eu.hansolo.mcp.tasks.jdbc;

import eu.hansolo.mcp.tasks.TaskRecord;
import eu.hansolo.mcp.tasks.TaskStatus;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    void theAttemptTheRestartableFlagAndTheStoredCallSurviveARoundTrip() {
        JdbcTaskStore store = store();
        JsonObject call = Json.createObjectBuilder().add("arguments", Json.createObjectBuilder().add("n", "7")).add("caller", Json.createObjectBuilder().add("subject", "alice")).build();
        TaskRecord created = TaskRecord.create("r1", "alice", "job", T0, 3_600_000L, 2_000L, "worker-a", T0.plusSeconds(30)).restartable(call);
        store.create(created);
        assertEquals(created, store.find("r1").orElseThrow());
        TaskRecord again = created.edit(T0.plusSeconds(40), edit -> edit.nextAttempt().lease("worker-b", T0.plusSeconds(70)));
        assertTrue(store.replace(created, again));
        TaskRecord read = store.find("r1").orElseThrow();
        assertEquals(2, read.attempt());
        assertTrue(read.restartable());
        assertEquals(call, read.call());
        assertEquals("worker-b", read.workerId());
    }

    @Test
    void aTaskWithoutExpiryAndLeaseKeepsItsNulls() {
        JdbcTaskStore store = store();
        TaskRecord created = new TaskRecord("t2", "bob", "x", TaskStatus.WORKING, null, T0, T0, null, null, 500L, null, null, null, null, false, null, null, 1, false, null, null, null, 0, 0L);
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
    void findAllReturnsTheExistingTasksAndIgnoresUnknownDuplicateAndNullIds() {
        JdbcTaskStore store = store();
        store.create(TaskRecord.create("a", "alice", "x", T0, 10_000L, 100L, "w", T0.plusSeconds(30)));
        store.create(TaskRecord.create("b", "bob", "x", T0, 10_000L, 100L, "w", T0.plusSeconds(30)));
        Map<String, TaskRecord> found = store.findAll(Arrays.asList("b", "missing", "a", "b", null));
        assertEquals(Set.of("a", "b"), found.keySet(), "the order of the result is not part of the contract");
        assertEquals(store.find("a").orElseThrow(), found.get("a"));
        assertEquals("bob", found.get("b").owner());
        assertTrue(store.findAll(List.of()).isEmpty());
        assertTrue(store.findAll(List.of("missing")).isEmpty());
    }

    @Test
    void findAllCrossesTheChunkBoundary() {
        JdbcTaskStore store = store();
        int total = JdbcTaskStore.FIND_ALL_CHUNK * 2 + 5;
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            String id = "chunk-" + i;
            ids.add(id);
            store.create(TaskRecord.create(id, "alice", "x", T0, 10_000L, 100L, "w", T0.plusSeconds(30)));
        }
        ids.add("chunk-missing");
        Map<String, TaskRecord> found = store.findAll(ids);
        assertEquals(total, found.size());
        assertEquals("chunk-" + (total - 1), found.get("chunk-" + (total - 1)).id());
    }

    @Test
    void ddlHasSeparateIndexesOrInlineOnesForMysqlAndMariadb() {
        List<String> separate = JdbcTaskStore.ddl("mcp_tasks", "TEXT");
        assertEquals(4, separate.size());
        assertTrue(separate.get(3).startsWith("CREATE INDEX IF NOT EXISTS mcp_tasks_poll"));
        assertTrue(separate.get(1).startsWith("CREATE INDEX IF NOT EXISTS mcp_tasks_lease"));
        List<String> inline = JdbcTaskStore.ddl("mcp_tasks", "LONGTEXT", true);
        assertEquals(1, inline.size());
        assertTrue(inline.get(0).contains("result_json LONGTEXT"));
        assertTrue(inline.get(0).contains("INDEX mcp_tasks_lease (lease_until)"));
        assertTrue(inline.get(0).contains("INDEX mcp_tasks_poll (next_poll_at)"));
        assertTrue(inline.get(0).contains("handle_json LONGTEXT"));
        assertFalse(inline.get(0).contains("CREATE INDEX"));
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
        store.create(new TaskRecord("forever", "a", "x", TaskStatus.WORKING, null, T0, T0, null, null, 100L, null, null, null, null, false, "w", T0.plusSeconds(5), 1, false, null, null, null, 0, 0L));

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


    /** The table as 0.7.0 created it on H2: the current DDL without the three columns of 0.8.0. */
    private static final String TABLE_OF_0_7_0 = "CREATE TABLE mcp_tasks (id VARCHAR(64) PRIMARY KEY, owner_subject VARCHAR(512) NOT NULL, tool VARCHAR(256) NOT NULL, status VARCHAR(16) NOT NULL, status_message VARCHAR(2048), created_at BIGINT NOT NULL, last_updated_at BIGINT NOT NULL, ttl_ms BIGINT, expires_at BIGINT, poll_interval_ms BIGINT NOT NULL, result_json CLOB, error_json CLOB, input_requests_json CLOB NOT NULL, input_responses_json CLOB NOT NULL, cancel_requested BOOLEAN NOT NULL, worker_id VARCHAR(64), lease_until BIGINT, version_no BIGINT NOT NULL)";

    @Test
    void aTableFrom070IsRefusedWithAHintAndWorksAfterTheDocumentedAlterTable() throws Exception {
        DataSource dataSource = dataSource();
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute(TABLE_OF_0_7_0);
            statement.execute("INSERT INTO mcp_tasks (id, owner_subject, tool, status, status_message, created_at, last_updated_at, ttl_ms, expires_at, poll_interval_ms, result_json, error_json, input_requests_json, input_responses_json, cancel_requested, worker_id, lease_until, version_no) VALUES ('old', 'alice', 'report', 'working', NULL, 1000, 1000, NULL, NULL, 500, NULL, NULL, '{}', '{}', FALSE, 'worker-a', 5000, 3)");
        }
        IllegalStateException createFailed = assertThrows(IllegalStateException.class, () -> new JdbcTaskStore(dataSource, "mcp_tasks", true));
        assertTrue(createFailed.getMessage().contains("earlier version"), "create-schema cannot add the columns to the old table, and says what to do");
        JdbcTaskStore store = new JdbcTaskStore(dataSource, "mcp_tasks", false);

        IllegalStateException refused = assertThrows(IllegalStateException.class, store::init);
        assertTrue(refused.getMessage().contains("cannot be read"));
        assertTrue(refused.getMessage().contains("earlier version"));

        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE mcp_tasks ADD COLUMN attempt_no INT NOT NULL DEFAULT 1");
            statement.execute("ALTER TABLE mcp_tasks ADD COLUMN restartable BOOLEAN NOT NULL DEFAULT FALSE");
            statement.execute("ALTER TABLE mcp_tasks ADD COLUMN call_json CLOB");
        }
        assertThrows(IllegalStateException.class, store::init, "the columns of the external jobs are still missing");
        addExternalJobColumns(dataSource);
        store.init();

        TaskRecord old = store.find("old").orElseThrow();
        assertEquals(1, old.attempt());
        assertFalse(old.restartable());
        assertEquals(null, old.call());
        assertEquals("worker-a", old.workerId());
        assertEquals(3L, old.version());

        TaskRecord next = old.edit(T0, edit -> edit.nextAttempt().lease("worker-b", T0.plusSeconds(30)));
        assertTrue(store.replace(old, next));
        assertEquals(2, store.find("old").orElseThrow().attempt());

        TaskRecord fresh = TaskRecord.create("new", "alice", "job", T0, 3_600_000L, 2_000L, "worker-a", T0.plusSeconds(30)).restartable(Json.createObjectBuilder().add("tool", "job").build());
        store.create(fresh);
        assertEquals(fresh, store.find("new").orElseThrow());
    }


    private static void addExternalJobColumns(DataSource dataSource) throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE mcp_tasks ADD COLUMN handle_json CLOB");
            statement.execute("ALTER TABLE mcp_tasks ADD COLUMN next_poll_at BIGINT");
            statement.execute("ALTER TABLE mcp_tasks ADD COLUMN poll_failures INT NOT NULL DEFAULT 0");
            statement.execute("CREATE INDEX mcp_tasks_poll ON mcp_tasks (next_poll_at)");
        }
    }

    @Test
    void aTableFrom080NeedsTheFourStatementsOfTheExternalJobsAndKeepsItsTasks() throws Exception {
        DataSource dataSource = dataSource();
        JdbcTaskStore before = new JdbcTaskStore(dataSource, "mcp_tasks", true);
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE mcp_tasks DROP COLUMN handle_json");
            statement.execute("DROP INDEX mcp_tasks_poll");
            statement.execute("ALTER TABLE mcp_tasks DROP COLUMN next_poll_at");
            statement.execute("ALTER TABLE mcp_tasks DROP COLUMN poll_failures");
            statement.execute("INSERT INTO mcp_tasks (id, owner_subject, tool, status, created_at, last_updated_at, poll_interval_ms, input_requests_json, input_responses_json, cancel_requested, worker_id, lease_until, attempt_no, restartable, version_no) VALUES ('old', 'alice', 'report', 'working', 1000, 1000, 500, '{}', '{}', FALSE, 'worker-a', 5000, 2, FALSE, 4)");
        }
        IllegalStateException refused = assertThrows(IllegalStateException.class, before::init);
        assertTrue(refused.getMessage().contains("earlier version"));

        addExternalJobColumns(dataSource);
        before.init();
        TaskRecord old = before.find("old").orElseThrow();
        assertEquals(null, old.handle());
        assertEquals(null, old.nextPollAt());
        assertEquals(0, old.pollFailures());
        assertEquals(4L, old.version());
    }

    @Test
    void anExternalTaskKeepsItsHandleAndPollTimeAndIsFoundWhenDue() {
        JdbcTaskStore store = new JdbcTaskStore(dataSource(), "mcp_tasks", true);
        var handle = Json.createObjectBuilder().add("job", "build-42").build();
        TaskRecord created = TaskRecord.createExternal("ext", "alice", "build", T0, 3_600_000L, 2_000L, handle, T0.plusSeconds(5));
        store.create(created);
        assertEquals(created, store.find("ext").orElseThrow());
        assertTrue(store.pollDue(T0.plusSeconds(4), 10).isEmpty());
        assertEquals(List.of("ext"), store.pollDue(T0.plusSeconds(5), 10));
        assertTrue(store.leaseExpired(T0.plusSeconds(3600), 10).isEmpty(), "no lease, so the lease sweep never touches it");

        TaskRecord claimed = created.edit(T0, false, edit -> edit.nextPollAt(T0.plusSeconds(35)));
        assertTrue(store.replace(created, claimed));
        assertTrue(store.pollDue(T0.plusSeconds(6), 10).isEmpty());

        TaskRecord failing = claimed.edit(T0, false, edit -> edit.pollFailures(2).nextPollAt(T0.plusSeconds(10)));
        assertTrue(store.replace(claimed, failing));
        assertEquals(2, store.find("ext").orElseThrow().pollFailures());

        TaskRecord done = failing.edit(T0, edit -> edit.status(TaskStatus.COMPLETED).result(Json.createObjectBuilder().add("ok", true).build()));
        assertTrue(store.replace(failing, done));
        TaskRecord stored = store.find("ext").orElseThrow();
        assertEquals(null, stored.handle(), "the handle may hold credentials, so it goes when the task ends");
        assertEquals(null, stored.nextPollAt());
        assertTrue(store.pollDue(T0.plusSeconds(3600), 10).isEmpty());
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
