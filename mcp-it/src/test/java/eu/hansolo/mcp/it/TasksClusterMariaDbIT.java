package eu.hansolo.mcp.it;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;

import java.time.Duration;


/** {@link TasksClusterBase} on MariaDB: the store creates its table with {@code LONGTEXT} columns and inline indexes. */
class TasksClusterMariaDbIT extends TasksClusterBase {

    @Override
    protected Database database(Network network) {
        // the official image starts a temporary server to run its init, then the real one: "ready for connections" is logged twice
        GenericContainer<?> mariadb = new GenericContainer<>("mariadb:11").withNetwork(network).withNetworkAliases("mariadb").withEnv("MARIADB_ROOT_PASSWORD", "root").withEnv("MARIADB_DATABASE", "mcp").withEnv("MARIADB_USER", "mcp").withEnv("MARIADB_PASSWORD", "mcp")
                                                                                 .waitingFor(Wait.forLogMessage(".*ready for connections.*\\n", 2)).withStartupTimeout(Duration.ofMinutes(2));
        return new Database(mariadb, "jdbc:mariadb://mariadb:3306/mcp", "mcp", "mcp");
    }
}
