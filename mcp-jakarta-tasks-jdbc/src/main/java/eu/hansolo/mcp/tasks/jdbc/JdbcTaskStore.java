package eu.hansolo.mcp.tasks.jdbc;

import eu.hansolo.mcp.server.Settings;
import eu.hansolo.mcp.tasks.TaskRecord;
import eu.hansolo.mcp.tasks.TaskStatus;
import eu.hansolo.mcp.tasks.TaskStore;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;
import jakarta.json.Json;
import jakarta.json.JsonObject;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;
import java.io.PrintWriter;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * A {@link TaskStore} in a relational database, so that every server instance sees the same tasks and they survive a restart. Plain
 * JDBC, one table, one statement per operation; compare and set is {@code UPDATE ... WHERE id = ? AND version_no = ?}.
 *
 * <p>Put this module's jar in the application and set {@code mcp.tasks.datasource} to the JNDI name of a {@code DataSource}; the
 * server then uses this store instead of the in-memory one. Without that setting (and without {@code mcp.tasks.jdbc.url}) the store
 * reports itself as not available and the in-memory store stays in use. Create the table with the DDL from {@link #ddl}, or set
 * {@code mcp.tasks.jdbc.create-schema=true} to have it created at startup (PostgreSQL and H2; elsewhere run the DDL yourself).
 * {@code mcp.tasks.jdbc.table} renames the table (default {@code mcp_tasks}).
 *
 * <p>Times are stored as epoch milliseconds, JSON as text. The statements use no vendor features, so other databases that accept
 * standard SQL should work, but only H2 and PostgreSQL are meant to be used.
 */
@Singleton
public class JdbcTaskStore implements TaskStore {

    private static final Logger  LOG   = Logger.getLogger(JdbcTaskStore.class.getName());
    private static final Pattern TABLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");
    private static final String  COLUMNS = "id, owner_subject, tool, status, status_message, created_at, last_updated_at, ttl_ms, expires_at, poll_interval_ms, result_json, error_json, input_requests_json, input_responses_json, cancel_requested, worker_id, lease_until, version_no";

    private final String  jndiName;
    private final String  url;
    private final String  user;
    private final String  password;
    private final String  table;
    private final boolean createSchema;

    private volatile DataSource dataSource;


    /**
     * For the container: reads {@code mcp.tasks.datasource} (JNDI name of a {@code DataSource}; use this in production) or
     * {@code mcp.tasks.jdbc.url} with {@code mcp.tasks.jdbc.user} and {@code mcp.tasks.jdbc.password} (a connection per operation through
     * {@code DriverManager}: for tests and small setups), and {@code mcp.tasks.jdbc.table} and {@code mcp.tasks.jdbc.create-schema}.
     * With neither of the first two set the store is {@linkplain #available() not available} and the server ignores it.
     */
    public JdbcTaskStore() {
        this.jndiName     = Settings.get("mcp.tasks.datasource");
        this.url          = Settings.get("mcp.tasks.jdbc.url");
        this.user         = Settings.get("mcp.tasks.jdbc.user");
        this.password     = Settings.get("mcp.tasks.jdbc.password");
        this.table        = checkTable(Settings.get("mcp.tasks.jdbc.table") != null ? Settings.get("mcp.tasks.jdbc.table") : "mcp_tasks");
        this.createSchema = Settings.getBoolean("mcp.tasks.jdbc.create-schema", false);
        if (jndiName != null && url != null) {
            throw new IllegalStateException("Set either mcp.tasks.datasource or mcp.tasks.jdbc.url, not both");
        }
    }

    /** For code that has its own {@code DataSource}: uses it and the default table {@code mcp_tasks}. */
    public JdbcTaskStore(DataSource dataSource) {
        this(dataSource, "mcp_tasks", false);
    }

    public JdbcTaskStore(DataSource dataSource, String table, boolean createSchema) {
        this.jndiName     = null;
        this.url          = null;
        this.user         = null;
        this.password     = null;
        this.table        = checkTable(table);
        this.createSchema = createSchema;
        this.dataSource   = dataSource;
        if (createSchema) {
            createSchema();
        }
    }


    @Override
    public boolean available() {
        return dataSource != null || jndiName != null || url != null;
    }

    private static String checkTable(String name) {
        if (!TABLE.matcher(name).matches()) {
            throw new IllegalStateException("mcp.tasks.jdbc.table '" + name + "' is not a plain SQL identifier");
        }
        return name;
    }

    /** Looks up the data source, creates the table if asked to, and checks that the table can be read, so a wrong setup fails the deployment. */
    @PostConstruct
    void init() {
        if (!available()) {
            return;
        }
        dataSource();
        if (createSchema) {
            createSchema();
        }
        try (Connection connection = dataSource().getConnection(); Statement statement = connection.createStatement(); ResultSet ignored = statement.executeQuery("SELECT " + COLUMNS + " FROM " + table + " WHERE 1 = 0")) {
            // the table exists and has the columns
        } catch (SQLException e) {
            throw new IllegalStateException("The task table '" + table + "' cannot be read (" + e.getMessage() + "). Create it with JdbcTaskStore.ddl(...) or set mcp.tasks.jdbc.create-schema=true", e);
        }
    }

    /**
     * The statements that create the task table and its indexes. {@code textType} is the column type for JSON text: {@code TEXT} on PostgreSQL,
     * {@code LONGTEXT} on MariaDB and MySQL (see {@link #ddl(String, String, boolean)}), {@code CLOB} on H2 and the others.
     */
    public static List<String> ddl(String table, String textType) {
        return ddl(table, textType, false);
    }

    /**
     * Like {@link #ddl(String, String)}, optionally with the indexes inside the {@code CREATE TABLE} statement. MySQL has no
     * {@code CREATE INDEX IF NOT EXISTS}, so for MariaDB and MySQL the indexes are declared inline ({@code inlineIndexes} = true).
     */
    public static List<String> ddl(String table, String textType, boolean inlineIndexes) {
        checkTable(table);
        String columns = "id VARCHAR(64) PRIMARY KEY, owner_subject VARCHAR(512) NOT NULL, tool VARCHAR(256) NOT NULL, status VARCHAR(16) NOT NULL, status_message VARCHAR(2048), created_at BIGINT NOT NULL, last_updated_at BIGINT NOT NULL, ttl_ms BIGINT, expires_at BIGINT, poll_interval_ms BIGINT NOT NULL, result_json " + textType + ", error_json " + textType + ", input_requests_json " + textType + " NOT NULL, input_responses_json " + textType + " NOT NULL, cancel_requested BOOLEAN NOT NULL, worker_id VARCHAR(64), lease_until BIGINT, version_no BIGINT NOT NULL";
        if (inlineIndexes) {
            return List.of("CREATE TABLE IF NOT EXISTS " + table + " (" + columns + ", INDEX " + table + "_lease (lease_until), INDEX " + table + "_expires (expires_at))");
        }
        return List.of("CREATE TABLE IF NOT EXISTS " + table + " (" + columns + ")",
                       "CREATE INDEX IF NOT EXISTS " + table + "_lease ON " + table + " (lease_until)",
                       "CREATE INDEX IF NOT EXISTS " + table + "_expires ON " + table + " (expires_at)");
    }

    private void createSchema() {
        try (Connection connection = dataSource().getConnection(); Statement statement = connection.createStatement()) {
            String product = connection.getMetaData().getDatabaseProductName();
            String name    = product == null ? "" : product.toLowerCase(java.util.Locale.ROOT);
            boolean mysql  = name.contains("mysql") || name.contains("mariadb");
            String text    = name.contains("postgres") ? "TEXT" : mysql ? "LONGTEXT" : "CLOB";
            for (String sql : ddl(table, text, mysql)) {
                statement.execute(sql);
            }
        } catch (SQLException e) {
            throw failure("Could not create the task table", e);
        }
    }

    private DataSource dataSource() {
        DataSource found = dataSource;
        if (found == null) {
            synchronized (this) {
                found = dataSource;
                if (found == null) {
                    if (url != null) {
                        dataSource = found = new DriverManagerDataSource(url, user, password);
                    } else if (jndiName != null) {
                        try {
                            Object looked = new InitialContext().lookup(jndiName);
                            if (!(looked instanceof DataSource ds)) {
                                throw new IllegalStateException("mcp.tasks.datasource '" + jndiName + "' is not a DataSource (" + looked.getClass().getName() + ")");
                            }
                            dataSource = found = ds;
                        } catch (NamingException e) {
                            throw new IllegalStateException("mcp.tasks.datasource '" + jndiName + "' could not be looked up", e);
                        }
                    } else {
                        throw new IllegalStateException("The JDBC task store is not configured: set mcp.tasks.datasource or mcp.tasks.jdbc.url");
                    }
                }
            }
        }
        return found;
    }

    /** One new connection per call, from the driver on the classpath. No pooling: meant for tests and small setups. */
    private static final class DriverManagerDataSource implements DataSource {

        private final String url;
        private final String user;
        private final String password;


        private DriverManagerDataSource(String url, String user, String password) {
            this.url      = url;
            this.user     = user;
            this.password = password;
            // a driver in the application's own lib directory is invisible to DriverManager if the server initialised it earlier: loading the drivers through this class's loader registers them
            try {
                for (Driver ignored : ServiceLoader.load(Driver.class, JdbcTaskStore.class.getClassLoader())) {
                    // creating the driver registers it
                }
            } catch (ServiceConfigurationError e) {
                LOG.log(Level.WARNING, "A JDBC driver could not be loaded", e);
            }
        }


        @Override
        public Connection getConnection() throws SQLException {
            return user == null ? DriverManager.getConnection(url) : DriverManager.getConnection(url, user, password);
        }

        @Override
        public Connection getConnection(String username, String pass) throws SQLException {
            return DriverManager.getConnection(url, username, pass);
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public <T> T unwrap(Class<T> type) throws SQLException {
            throw new SQLException("Not a wrapper");
        }

        @Override
        public boolean isWrapperFor(Class<?> type) {
            return false;
        }
    }


    @Override
    public void create(TaskRecord task) {
        String sql = "INSERT INTO " + table + " (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = dataSource().getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, task.id());
            statement.setString(2, task.owner());
            statement.setString(3, task.tool());
            bindMutable(statement, task, 4);
            statement.setLong(6, task.createdAt().toEpochMilli());
            statement.setLong(7, task.lastUpdatedAt().toEpochMilli());
            setLong(statement, 8, task.ttlMs());
            setLong(statement, 9, task.expiresAt() == null ? null : task.expiresAt().toEpochMilli());
            statement.setLong(10, task.pollIntervalMs());
            statement.setString(11, json(task.result()));
            statement.setString(12, json(task.error()));
            statement.setString(13, task.inputRequests().toString());
            statement.setString(14, task.inputResponses().toString());
            statement.setBoolean(15, task.cancelRequested());
            statement.setString(16, task.workerId());
            setLong(statement, 17, task.leaseUntil() == null ? null : task.leaseUntil().toEpochMilli());
            statement.setLong(18, task.version());
            statement.executeUpdate();
        } catch (SQLException e) {
            if (e.getSQLState() != null && e.getSQLState().startsWith("23")) {
                throw new IllegalStateException("Task " + task.id() + " exists", e);
            }
            throw failure("Could not store a new task", e);
        }
    }

    /** Binds status (index 4) and status message (index 5). */
    private static void bindMutable(PreparedStatement statement, TaskRecord task, int first) throws SQLException {
        statement.setString(first, task.status().wire());
        if (task.statusMessage() == null) {
            statement.setNull(first + 1, Types.VARCHAR);
        } else {
            statement.setString(first + 1, task.statusMessage().length() > 2048 ? task.statusMessage().substring(0, 2048) : task.statusMessage());
        }
    }

    /** Ids per query: well under the parameter limits of the common databases (Oracle 1000 in an IN list, SQL Server 2100 parameters). */
    static final int FIND_ALL_CHUNK = 200;

    @Override
    public Map<String, TaskRecord> findAll(Collection<String> ids) {
        Map<String, TaskRecord> found = new LinkedHashMap<>();
        List<String> wanted = new ArrayList<>(new LinkedHashSet<>(ids.stream().filter(java.util.Objects::nonNull).toList()));
        if (wanted.isEmpty()) {
            return found;
        }
        try (Connection connection = dataSource().getConnection()) {
            for (int from = 0; from < wanted.size(); from += FIND_ALL_CHUNK) {
                List<String> chunk = wanted.subList(from, Math.min(wanted.size(), from + FIND_ALL_CHUNK));
                String sql = "SELECT " + COLUMNS + " FROM " + table + " WHERE id IN (" + "?, ".repeat(chunk.size() - 1) + "?)";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    for (int i = 0; i < chunk.size(); i++) {
                        statement.setString(i + 1, chunk.get(i));
                    }
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) {
                            TaskRecord record = read(rows);
                            found.put(record.id(), record);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            throw failure("Could not read tasks", e);
        }
        return found;
    }

    @Override
    public Optional<TaskRecord> find(String id) {
        String sql = "SELECT " + COLUMNS + " FROM " + table + " WHERE id = ?";
        try (Connection connection = dataSource().getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw failure("Could not read a task", e);
        }
    }

    @Override
    public boolean replace(TaskRecord current, TaskRecord next) {
        if (next.version() != current.version() + 1 || !next.id().equals(current.id())) {
            throw new IllegalArgumentException("next must be the successor of current");
        }
        String sql = "UPDATE " + table + " SET status = ?, status_message = ?, last_updated_at = ?, result_json = ?, error_json = ?, input_requests_json = ?, input_responses_json = ?, cancel_requested = ?, worker_id = ?, lease_until = ?, version_no = ? WHERE id = ? AND version_no = ?";
        try (Connection connection = dataSource().getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            bindMutable(statement, next, 1);
            statement.setLong(3, next.lastUpdatedAt().toEpochMilli());
            statement.setString(4, json(next.result()));
            statement.setString(5, json(next.error()));
            statement.setString(6, next.inputRequests().toString());
            statement.setString(7, next.inputResponses().toString());
            statement.setBoolean(8, next.cancelRequested());
            statement.setString(9, next.workerId());
            setLong(statement, 10, next.leaseUntil() == null ? null : next.leaseUntil().toEpochMilli());
            statement.setLong(11, next.version());
            statement.setString(12, current.id());
            statement.setLong(13, current.version());
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw failure("Could not update a task", e);
        }
    }

    @Override
    public List<String> leaseExpired(Instant now, int limit) {
        return ids("SELECT id FROM " + table + " WHERE status IN ('" + TaskStatus.WORKING.wire() + "', '" + TaskStatus.INPUT_REQUIRED.wire() + "') AND lease_until IS NOT NULL AND lease_until < ?", now, limit);
    }

    @Override
    public List<String> expired(Instant now, int limit) {
        return ids("SELECT id FROM " + table + " WHERE expires_at IS NOT NULL AND expires_at <= ?", now, limit);
    }

    private List<String> ids(String sql, Instant bound, int limit) {
        try (Connection connection = dataSource().getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, bound.toEpochMilli());
            statement.setMaxRows(Math.max(1, limit));
            List<String> found = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    found.add(rows.getString(1));
                }
            }
            return found;
        } catch (SQLException e) {
            throw failure("Could not look for tasks", e);
        }
    }

    @Override
    public void delete(String id) {
        try (Connection connection = dataSource().getConnection(); PreparedStatement statement = connection.prepareStatement("DELETE FROM " + table + " WHERE id = ?")) {
            statement.setString(1, id);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw failure("Could not delete a task", e);
        }
    }


    private static TaskRecord read(ResultSet rows) throws SQLException {
        long ttl = rows.getLong(8);
        Long ttlMs = rows.wasNull() ? null : ttl;
        long expires = rows.getLong(9);
        Instant expiresAt = rows.wasNull() ? null : Instant.ofEpochMilli(expires);
        long lease = rows.getLong(17);
        Instant leaseUntil = rows.wasNull() ? null : Instant.ofEpochMilli(lease);
        return new TaskRecord(rows.getString(1), rows.getString(2), rows.getString(3), TaskStatus.fromWire(rows.getString(4)), rows.getString(5), Instant.ofEpochMilli(rows.getLong(6)), Instant.ofEpochMilli(rows.getLong(7)), ttlMs, expiresAt, rows.getLong(10), object(rows.getString(11)), object(rows.getString(12)), object(rows.getString(13)), object(rows.getString(14)), rows.getBoolean(15), rows.getString(16), leaseUntil, rows.getLong(18));
    }

    private static void setLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static String json(JsonObject value) {
        return value == null ? null : value.toString();
    }

    private static JsonObject object(String text) {
        return text == null ? null : Json.createReader(new StringReader(text)).readObject();
    }

    private static IllegalStateException failure(String what, SQLException cause) {
        return new IllegalStateException(what + ": " + cause.getMessage(), cause);
    }
}
