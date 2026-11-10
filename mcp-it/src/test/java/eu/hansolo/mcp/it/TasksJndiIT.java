package eu.hansolo.mcp.it;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JDBC task store through a container-managed {@code DataSource} (setting {@code mcp.tasks.datasource}), the production path.
 * Payara Micro gets a JDBC connection pool and the resource {@code jdbc/mcp} from a pre-boot command file, with the PostgreSQL driver
 * on its library path. WildFly gets the driver as a module and the data source {@code java:jboss/datasources/mcp} from a CLI script
 * that runs before the server starts. Open Liberty gets the driver in its server directory and the data source {@code jdbc/mcp} from a
 * file in {@code configDropins/overrides}, so the shared {@code server.xml} stays as it is.
 */
class TasksJndiIT {

    private static final String TASKS        = "io.modelcontextprotocol/tasks";
    private static final String PAYARA_JNDI  = "jdbc/mcp";
    private static final String WILDFLY_JNDI = "java:jboss/datasources/mcp";

    private static Network             network;
    private static GenericContainer<?> postgres;
    private static GenericContainer<?> server;
    private static McpClient           client;


    @BeforeAll
    static void start() throws Exception {
        Assumptions.assumeTrue(ServerFixture.dockerAvailable(), "Docker is not available: skipping integration tests");
        String  name    = ServerFixture.serverName();
        boolean wildfly = name.equals("wildfly");
        boolean liberty = name.equals("liberty");
        network = Network.newNetwork();
        postgres = new GenericContainer<>("postgres:17-alpine").withNetwork(network).withNetworkAliases("postgres").withEnv("POSTGRES_PASSWORD", "mcp").withEnv("POSTGRES_USER", "mcp").withEnv("POSTGRES_DB", "mcp")
                                                              .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2)).withStartupTimeout(Duration.ofMinutes(2));
        postgres.start();
        Path driver = Path.of(org.postgresql.ds.PGSimpleDataSource.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        server = ServerFixture.create(ServerFixture.fixtureWar(), Map.of("MCP_TASKS_ENABLED", "true", "MCP_TASKS_DATASOURCE", wildfly ? WILDFLY_JNDI : PAYARA_JNDI, "MCP_TASKS_JDBC_CREATE_SCHEMA", "true"), false).withNetwork(network);
        if (wildfly) {
            // the CLI script runs offline against standalone.xml, then the server starts as usual
            server.withCopyFileToContainer(MountableFile.forHostPath(driver), "/tmp/postgresql.jar").withCopyFileToContainer(MountableFile.forClasspathResource("wildfly/mcp-datasource.cli"), "/tmp/mcp-datasource.cli");
            server.withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("/bin/sh", "-c", "/opt/jboss/wildfly/bin/jboss-cli.sh --file=/tmp/mcp-datasource.cli && exec /opt/jboss/wildfly/bin/standalone.sh -b 0.0.0.0"));
        } else if (liberty) {
            // the file in configDropins/overrides is merged into the shared server.xml
            server.withCopyFileToContainer(MountableFile.forHostPath(driver), "/opt/ol/wlp/usr/servers/defaultServer/postgresql.jar").withCopyFileToContainer(MountableFile.forClasspathResource("liberty/datasource.xml"), "/opt/ol/wlp/usr/servers/defaultServer/configDropins/overrides/datasource.xml");
        } else {
            server.withCopyFileToContainer(MountableFile.forHostPath(driver), "/opt/payara/libs/postgresql.jar").withCopyFileToContainer(MountableFile.forClasspathResource("payara/preboot.txt"), "/opt/payara/preboot.txt")
                  .withCommand("--deploymentDir", "/opt/payara/deployments", "--addLibs", "/opt/payara/libs/postgresql.jar", "--prebootcommandfile", "/opt/payara/preboot.txt");
        }
        server.start();
        client = new McpClient(server.getHost(), server.getMappedPort(ServerFixture.httpPort()));
        ServerFixture.awaitDeployed(server, client);
    }

    @AfterAll
    static void stop() {
        for (GenericContainer<?> container : new GenericContainer<?>[] {server, postgres}) {
            if (container != null) {
                try {
                    container.stop();
                } catch (RuntimeException ignored) {
                    // already gone
                }
            }
        }
        if (network != null) {
            network.close();
        }
    }


    private static McpClient.Reply rpc(String method, String name, JsonObjectBuilder params) throws Exception {
        JsonObject capabilities = Json.createObjectBuilder().add("extensions", Json.createObjectBuilder().add(TASKS, Json.createObjectBuilder())).build();
        JsonObject meta = Json.createObjectBuilder().add("io.modelcontextprotocol/protocolVersion", "2026-07-28").add("io.modelcontextprotocol/clientInfo", Json.createObjectBuilder().add("name", "mcp-it").add("version", "0")).add("io.modelcontextprotocol/clientCapabilities", capabilities).build();
        String body = Json.createObjectBuilder().add("jsonrpc", "2.0").add("id", 1).add("method", method).add("params", params.add("_meta", meta)).build().toString();
        return client.post(McpClient.standardHeaders(method, name), body);
    }

    private static String startDemo(String mode) throws Exception {
        McpClient.Reply reply = rpc("tools/call", "task_demo", Json.createObjectBuilder().add("name", "task_demo").add("arguments", Json.createObjectBuilder().add("mode", mode).add("millis", 200)));
        assertEquals(200, reply.status(), reply.body());
        JsonObject result = reply.json().getJsonObject("result");
        assertEquals("task", result.getString("resultType"), reply.body());
        return result.getString("taskId");
    }

    private static JsonObject awaitStatus(String taskId, String status) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        JsonObject last = null;
        while (System.currentTimeMillis() < deadline) {
            McpClient.Reply reply = rpc("tasks/get", taskId, Json.createObjectBuilder().add("taskId", taskId));
            assertEquals(200, reply.status(), reply.body());
            last = reply.json().getJsonObject("result");
            if (last.getString("status").equals(status)) {
                return last;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Task never reached " + status + "; last seen: " + last);
    }


    @Test
    void tasksAreKeptInTheDatabaseBehindTheContainersDataSource() throws Exception {
        String id = startDemo("quick");
        JsonObject done = awaitStatus(id, "completed");
        assertEquals("finished quick", done.getJsonObject("result").getJsonArray("content").getJsonObject(0).getString("text"));
        var rows = postgres.execInContainer("psql", "-U", "mcp", "-d", "mcp", "-t", "-A", "-c", "select count(*) from mcp_tasks where id = '" + id + "'");
        assertEquals("1", rows.getStdout().trim(), rows.getStdout() + rows.getStderr());
    }

    @Test
    void inputAndCancelWorkThroughTheSameStore() throws Exception {
        String ask = startDemo("ask");
        assertTrue(awaitStatus(ask, "input_required").getJsonObject("inputRequests").containsKey("confirm"));
        JsonObject answer = Json.createObjectBuilder().add("confirm", Json.createObjectBuilder().add("action", "accept").add("content", Json.createObjectBuilder().add("why", "via jndi"))).build();
        assertEquals(200, rpc("tasks/update", ask, Json.createObjectBuilder().add("taskId", ask).add("inputResponses", answer)).status());
        assertEquals("confirmed: via jndi", awaitStatus(ask, "completed").getJsonObject("result").getJsonArray("content").getJsonObject(0).getString("text"));
        String wait = startDemo("wait");
        assertEquals(200, rpc("tasks/cancel", wait, Json.createObjectBuilder().add("taskId", wait)).status());
        awaitStatus(wait, "cancelled");
    }

    @Test
    void theServerDoesNotFallBackToTheMemoryStore() {
        assertFalse(server.getLogs().contains("tasks are kept in memory"), server.getLogs());
    }
}
