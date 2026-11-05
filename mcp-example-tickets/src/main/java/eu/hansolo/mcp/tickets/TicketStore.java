package eu.hansolo.mcp.tickets;

import java.util.List;
import java.util.Optional;

/** The persistence the MCP layer depends on. {@link JpaTicketStore} is the real one; tests use an in-memory fake. */
public interface TicketStore {

    /** @param text case-insensitive text in title or description, or null; @param status or null */
    SearchResult search(String text, Status status, int limit, int offset);

    Optional<TicketView> find(long id);

    TicketView create(String title, String description, Priority priority, String createdBy);

    /** Closes an open ticket (closing a closed one changes nothing); empty if there is no such ticket. */
    Optional<TicketView> close(long id, String resolution);

    Stats stats();

    /** Ticket numbers whose decimal form starts with the prefix, ascending, at most {@code limit}. */
    List<Long> idsStartingWith(String prefix, int limit);

    long count();
}
