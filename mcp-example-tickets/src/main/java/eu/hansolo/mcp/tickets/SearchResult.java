package eu.hansolo.mcp.tickets;

import eu.hansolo.mcp.api.McpParam;

import java.util.List;

/** One page of search results. */
public record SearchResult(
        @McpParam(description = "The tickets on this page, newest first") List<TicketView> tickets,
        @McpParam(description = "How many tickets match in total") long total,
        @McpParam(description = "The offset to pass to get the next page, absent on the last page") java.util.Optional<Integer> nextOffset) {
}
