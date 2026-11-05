package eu.hansolo.mcp.tickets;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import eu.hansolo.mcp.api.McpParam;
import eu.hansolo.mcp.api.McpPrompt;
import eu.hansolo.mcp.protocol.McpProtocolException;

/** A prompt a user picks explicitly (a slash command in most clients). */
@ApplicationScoped
public class TicketPrompts {

    private TicketStore store;


    /** For the CDI proxy. */
    TicketPrompts() {
    }

    @Inject
    public TicketPrompts(TicketStore store) {
        this.store = store;
    }


    @McpPrompt(description = "Asks the model to triage a ticket: assess urgency, suggest a priority and propose next steps.", scopes = {"tickets:read"})
    public String triage(@McpParam(description = "Ticket number") String ticketId) {
        long number;
        try {
            number = Long.parseLong(ticketId.trim());
        } catch (NumberFormatException e) {
            throw McpProtocolException.invalidArguments("ticketId must be a ticket number");
        }
        TicketView ticket = store.find(number).orElseThrow(() -> McpProtocolException.invalidArguments("There is no ticket " + number));
        return "Please triage this support ticket. Assess how urgent it is, say whether the priority should change, and propose the next steps.\n\n"
                + "Ticket " + ticket.id() + " [" + ticket.status() + ", " + ticket.priority() + "]\n"
                + "Title: " + ticket.title() + "\n"
                + "Reported by: " + ticket.createdBy() + " at " + ticket.createdAt() + "\n\n"
                + ticket.description();
    }
}
