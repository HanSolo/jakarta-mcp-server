package eu.hansolo.mcp.it;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;

import java.time.Duration;


/** {@link TasksClusterBase} on PostgreSQL. */
class TasksClusterIT extends TasksClusterBase {

    @Override
    protected Database database(Network network) {
        GenericContainer<?> postgres = new GenericContainer<>("postgres:17-alpine").withNetwork(network).withNetworkAliases("postgres").withEnv("POSTGRES_PASSWORD", "mcp").withEnv("POSTGRES_USER", "mcp").withEnv("POSTGRES_DB", "mcp")
                                                                                   .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2)).withStartupTimeout(Duration.ofMinutes(2));
        return new Database(postgres, "jdbc:postgresql://postgres:5432/mcp", "mcp", "mcp");
    }
}
