package eu.hansolo.mcp.tickets;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import eu.hansolo.mcp.api.McpResource;
import eu.hansolo.mcp.protocol.ErrorCodes;
import eu.hansolo.mcp.protocol.McpProtocolException;

/** Tickets as resources: something an application can attach to a conversation, as opposed to something a model calls. */
@ApplicationScoped
public class TicketResources {

    private TicketStore store;


    /** For the CDI proxy. */
    TicketResources() {
    }

    @Inject
    public TicketResources(TicketStore store) {
        this.store = store;
    }


    @McpResource(uriTemplate = "ticket://{id}", name = "ticket", description = "One ticket as JSON.", scopes = {"tickets:read"})
    public TicketView ticket(String id) {
        long number;
        try {
            number = Long.parseLong(id);
        } catch (NumberFormatException e) {
            throw McpProtocolException.invalidArguments("A ticket number is a decimal number");
        }
        return store.find(number).orElseThrow(() -> new McpProtocolException(200, ErrorCodes.INVALID_PARAMS, "Resource not found", Json.createObjectBuilder().add("uri", "ticket://" + number).build()));
    }

    @McpResource(uri = "ticket://stats", name = "ticket-stats", description = "How many tickets are open (by priority) and closed.", scopes = {"tickets:read"})
    public Stats stats() {
        return store.stats();
    }
}
