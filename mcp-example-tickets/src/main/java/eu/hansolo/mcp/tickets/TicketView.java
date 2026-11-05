package eu.hansolo.mcp.tickets;

import eu.hansolo.mcp.api.McpParam;

import java.util.Optional;

/** What the MCP clients see of a ticket: a flat, JSON-friendly copy that never exposes the entity. */
public record TicketView(
        @McpParam(description = "Ticket number") long id,
        @McpParam(description = "Short summary") String title,
        @McpParam(description = "Full description") String description,
        @McpParam(description = "OPEN or CLOSED") String status,
        @McpParam(description = "LOW, MEDIUM or HIGH") String priority,
        @McpParam(description = "Who created the ticket") String createdBy,
        @McpParam(description = "Creation time, ISO-8601 UTC") String createdAt,
        @McpParam(description = "How it was resolved, if closed") Optional<String> resolution) {

    static TicketView of(Ticket ticket) {
        return new TicketView(ticket.getId(), ticket.getTitle(), ticket.getDescription(), ticket.getStatus().name(), ticket.getPriority().name(), ticket.getCreatedBy(), ticket.getCreatedAt().toString(), Optional.ofNullable(ticket.getResolution()));
    }
}
