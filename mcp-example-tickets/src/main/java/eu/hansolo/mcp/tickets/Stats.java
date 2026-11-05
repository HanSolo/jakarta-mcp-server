package eu.hansolo.mcp.tickets;

import java.util.Map;

/** Counts for the {@code ticket://stats} resource. */
public record Stats(long open, long closed, Map<String, Long> openByPriority) {
}
