package eu.hansolo.mcp.tickets;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import eu.hansolo.mcp.api.CompletionResult;
import eu.hansolo.mcp.api.McpComplete;

import java.util.List;

/** Ticket-number suggestions while the user types an argument. They need the same scope as reading tickets: numbers are data too. */
@ApplicationScoped
public class TicketCompletions {

    private TicketStore store;


    /** For the CDI proxy. */
    TicketCompletions() {
    }

    @Inject
    public TicketCompletions(TicketStore store) {
        this.store = store;
    }


    @McpComplete(prompt = "triage", argument = "ticketId", scopes = {"tickets:read"})
    public CompletionResult forPrompt(String typed) {
        return numbers(typed);
    }

    @McpComplete(resourceTemplate = "ticket://{id}", argument = "id", scopes = {"tickets:read"})
    public CompletionResult forTemplate(String typed) {
        return numbers(typed);
    }

    private CompletionResult numbers(String typed) {
        List<String> ids = store.idsStartingWith(typed.trim(), 20).stream().map(String::valueOf).toList();
        return new CompletionResult(ids, null, null);
    }
}
