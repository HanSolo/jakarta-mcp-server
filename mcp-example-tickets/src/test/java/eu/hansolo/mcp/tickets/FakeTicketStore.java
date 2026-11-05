package eu.hansolo.mcp.tickets;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** An in-memory {@link TicketStore} so the MCP layer can be tested without a database. */
class FakeTicketStore implements TicketStore {

    private final List<TicketView> tickets = new ArrayList<>();


    @Override
    public SearchResult search(String text, Status status, int limit, int offset) {
        List<TicketView> matches = tickets.stream().filter(t -> status == null || t.status().equals(status.name())).filter(t -> text == null || t.title().toLowerCase(Locale.ROOT).contains(text.toLowerCase(Locale.ROOT)) || t.description().toLowerCase(Locale.ROOT).contains(text.toLowerCase(Locale.ROOT))).sorted((a, b) -> Long.compare(b.id(), a.id())).toList();
        List<TicketView> page = matches.stream().skip(offset).limit(limit).toList();
        int next = offset + page.size();
        return new SearchResult(page, matches.size(), next < matches.size() ? Optional.of(next) : Optional.empty());
    }

    @Override
    public Optional<TicketView> find(long id) {
        return tickets.stream().filter(t -> t.id() == id).findFirst();
    }

    @Override
    public TicketView create(String title, String description, Priority priority, String createdBy) {
        TicketView ticket = new TicketView(tickets.size() + 1L, title, description, Status.OPEN.name(), priority.name(), createdBy, Instant.EPOCH.toString(), Optional.empty());
        tickets.add(ticket);
        return ticket;
    }

    @Override
    public Optional<TicketView> close(long id, String resolution) {
        Optional<TicketView> found = find(id);
        if (found.isEmpty() || found.get().status().equals(Status.CLOSED.name())) {
            return found;
        }
        TicketView t      = found.get();
        TicketView closed = new TicketView(t.id(), t.title(), t.description(), Status.CLOSED.name(), t.priority(), t.createdBy(), t.createdAt(), Optional.ofNullable(resolution));
        tickets.set(tickets.indexOf(t), closed);
        return Optional.of(closed);
    }

    @Override
    public Stats stats() {
        Map<String, Long> open = new LinkedHashMap<>();
        for (Priority p : Priority.values()) {
            open.put(p.name(), tickets.stream().filter(t -> t.status().equals("OPEN") && t.priority().equals(p.name())).count());
        }
        return new Stats(open.values().stream().mapToLong(Long::longValue).sum(), tickets.stream().filter(t -> t.status().equals("CLOSED")).count(), open);
    }

    @Override
    public List<Long> idsStartingWith(String prefix, int limit) {
        return tickets.stream().map(TicketView::id).filter(id -> String.valueOf(id).startsWith(prefix)).limit(limit).toList();
    }

    @Override
    public long count() {
        return tickets.size();
    }
}
