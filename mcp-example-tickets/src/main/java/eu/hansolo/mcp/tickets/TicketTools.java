package eu.hansolo.mcp.tickets;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import eu.hansolo.mcp.api.Caller;
import eu.hansolo.mcp.api.Elicitation;
import eu.hansolo.mcp.api.InputRequests;
import eu.hansolo.mcp.api.McpParam;
import eu.hansolo.mcp.api.McpTool;
import eu.hansolo.mcp.api.ToolCall;
import eu.hansolo.mcp.api.ToolExecutionException;
import eu.hansolo.mcp.api.ToolResult;
import eu.hansolo.mcp.protocol.McpProtocolException;
import eu.hansolo.mcp.server.McpNotifications;
import eu.hansolo.mcp.server.McpRequestStates;

import java.util.Optional;

/**
 * The tickets as MCP tools. The scopes are the access policy: reading needs {@code tickets:read}, changing needs
 * {@code tickets:write}. Input is validated here, because it comes from a language model; a {@link ToolExecutionException} is
 * shown to the model so it can correct itself.
 */
@ApplicationScoped
public class TicketTools {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT     = 50;

    private TicketStore      store;
    private McpNotifications notifications;
    private McpRequestStates states;


    /** For the CDI proxy. */
    TicketTools() {
    }

    @Inject
    public TicketTools(TicketStore store, McpNotifications notifications, McpRequestStates states) {
        this.store         = store;
        this.notifications = notifications;
        this.states        = states;
    }


    @McpTool(description = "Searches tickets, newest first. Filter by text (in title or description) and/or status. Returns one page; pass nextOffset as offset to continue.", readOnlyHint = McpTool.Hint.TRUE, scopes = {"tickets:read"})
    public SearchResult search_tickets(@McpParam(description = "Text to look for, case-insensitive") Optional<String> text, @McpParam(description = "Only tickets with this status") Optional<Status> status, @McpParam(description = "Page size, 1 to 50 (default 20)") Optional<Integer> limit, @McpParam(description = "How many matches to skip (default 0)") Optional<Integer> offset) {
        int size = limit.orElse(DEFAULT_LIMIT);
        int skip = offset.orElse(0);
        if (size < 1 || size > MAX_LIMIT) {
            throw new ToolExecutionException("limit must be between 1 and " + MAX_LIMIT + ", got " + size);
        }
        if (skip < 0) {
            throw new ToolExecutionException("offset must not be negative, got " + skip);
        }
        return store.search(text.map(String::trim).filter(t -> !t.isEmpty()).orElse(null), status.orElse(null), size, skip);
    }

    @McpTool(description = "Returns one ticket by number.", readOnlyHint = McpTool.Hint.TRUE, scopes = {"tickets:read"})
    public TicketView get_ticket(@McpParam(description = "Ticket number") long id) {
        return store.find(id).orElseThrow(() -> new ToolExecutionException("There is no ticket " + id));
    }

    @McpTool(description = "Creates a ticket. It is recorded under the identity of the caller.", readOnlyHint = McpTool.Hint.FALSE, destructiveHint = McpTool.Hint.FALSE, scopes = {"tickets:write"})
    public TicketView create_ticket(@McpParam(description = "Short summary, up to 200 characters") String title, @McpParam(description = "What happened, up to 4000 characters") String description, @McpParam(description = "LOW, MEDIUM (default) or HIGH") Optional<Priority> priority, Caller caller) {
        String cleanTitle = title.trim();
        if (cleanTitle.isEmpty() || cleanTitle.length() > 200) {
            throw new ToolExecutionException("title must be 1 to 200 characters");
        }
        if (description.isBlank() || description.length() > 4000) {
            throw new ToolExecutionException("description must be 1 to 4000 characters");
        }
        TicketView created = store.create(cleanTitle, description.trim(), priority.orElse(Priority.MEDIUM), caller.subject());
        notifications.resourceUpdated("ticket://stats");   // clients that watch the statistics read them again
        return created;
    }

    /**
     * Closing is the one irreversible action here, so the user is asked first (multi round-trip: the client elicits a decision and
     * repeats the call with the answer). A client that cannot ask the user gets an error instead of a silent close.
     *
     * <p>The question and the answer belong together: the signed {@code requestState} carries the ticket number and the resolution the
     * user was asked about, for this tool and this caller. A retry with other arguments than the user confirmed is not carried out; the
     * user is asked again. Without the state, an answer could be replayed with any ticket number.
     */
    @McpTool(description = "Closes an open ticket after the user confirms. Requires a client that can ask the user (elicitation).", destructiveHint = McpTool.Hint.TRUE, idempotentHint = McpTool.Hint.TRUE, scopes = {"tickets:write"})
    public ToolResult close_ticket(@McpParam(description = "Ticket number") long id, @McpParam(description = "How it was resolved, up to 1000 characters") Optional<String> resolution, ToolCall call) {
        if (resolution.isPresent() && resolution.get().length() > 1000) {
            throw new ToolExecutionException("resolution must be at most 1000 characters");
        }
        store.find(id).orElseThrow(() -> new ToolExecutionException("There is no ticket " + id));
        String context = "close_ticket|" + call.caller().subject();
        String asked   = id + "|" + resolution.orElse("");
        Optional<Elicitation.Answer> answer = Elicitation.answer(call.inputResponses(), "confirm");
        if (answer.isPresent()) {
            // -32602 if the state is missing, altered, expired or was issued for another tool or caller
            String confirmed = states.require(call.requestState(), context);
            if (confirmed.equals(asked)) {
                if (!answer.get().accepted()) {
                    return ToolResult.error("Ticket " + id + " was not closed: the user declined.");
                }
                return store.close(id, resolution.orElse(null)).map(closed -> {
                    notifications.resourceUpdated("ticket://" + closed.id());
                    notifications.resourceUpdated("ticket://stats");
                    return ToolResult.text("Ticket " + closed.id() + " is now " + closed.status() + ".");
                }).orElseGet(() -> ToolResult.error("There is no ticket " + id));
            }
            // the user confirmed something else than this call asks for: ask again
        }
        if (!call.clientSupports("elicitation")) {
            throw McpProtocolException.missingClientCapability("elicitation");
        }
        // No ticket text in the message: Elicitation.form refuses texts that look like secrets, and a title such as "Password reset" would fail the call
        Elicitation.Form confirmation = Elicitation.form("Close ticket " + id + "?");
        return ToolResult.ask(InputRequests.of("confirm", confirmation), states.sign(asked, context));
    }
}
