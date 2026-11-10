package eu.hansolo.mcp.it;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;

import java.time.Duration;


/**
 * {@link TasksClusterBase} on MySQL 8.4 with MySQL's own driver (Connector/J): same {@code LONGTEXT} columns and inline indexes as on MariaDB, but a different
 * server, driver and authentication plugin.
 */
class TasksClusterMySqlIT extends TasksClusterBase {

    @Override
    protected Database database(Network network) {
        // The official image runs a temporary server (port 0) for its init and then the real one on port 3306: only the second line names port 3306.
        // MySQL 8 authenticates with caching_sha2_password, which over a connection without TLS needs the server's RSA key: allowPublicKeyRetrieval.
        GenericContainer<?> mysql = new GenericContainer<>("mysql:8.4").withNetwork(network).withNetworkAliases("mysql").withEnv("MYSQL_ROOT_PASSWORD", "root").withEnv("MYSQL_DATABASE", "mcp").withEnv("MYSQL_USER", "mcp").withEnv("MYSQL_PASSWORD", "mcp")
                                                                       .waitingFor(Wait.forLogMessage(".*ready for connections.*port: 3306.*\\n", 1)).withStartupTimeout(Duration.ofMinutes(2));
        return new Database(mysql, "jdbc:mysql://mysql:3306/mcp?useSSL=false&allowPublicKeyRetrieval=true", "mcp", "mcp");
    }
}
