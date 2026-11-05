package eu.hansolo.mcp.tickets;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceUnit;
import jakarta.persistence.TypedQuery;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Plain JPA with a resource-local persistence unit (see persistence.xml for why). Every call gets its own EntityManager and, for
 * writes, its own transaction. Queries are built from fixed strings plus named parameters, never from user text.
 */
@ApplicationScoped
public class JpaTicketStore implements TicketStore {

    private static final int MAX_ID_SCAN = 2000;

    @PersistenceUnit(unitName = "tickets")
    private EntityManagerFactory factory;


    @Override
    public SearchResult search(String text, Status status, int limit, int offset) {
        return read(em -> {
            List<String> conditions = new ArrayList<>();
            if (status != null) {
                conditions.add("t.status = :status");
            }
            if (text != null && !text.isBlank()) {
                conditions.add("(lower(t.title) like :pattern escape '!' or lower(t.description) like :pattern escape '!')");
            }
            String where = conditions.isEmpty() ? "" : " where " + String.join(" and ", conditions);
            TypedQuery<Long> count = em.createQuery("select count(t) from Ticket t" + where, Long.class);
            TypedQuery<Ticket> page = em.createQuery("select t from Ticket t" + where + " order by t.id desc", Ticket.class);
            if (status != null) {
                count.setParameter("status", status);
                page.setParameter("status", status);
            }
            if (text != null && !text.isBlank()) {
                String pattern = "%" + text.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
                count.setParameter("pattern", pattern);
                page.setParameter("pattern", pattern);
            }
            long total = count.getSingleResult();
            List<TicketView> views = new ArrayList<>();
            for (Ticket ticket : page.setFirstResult(offset).setMaxResults(limit).getResultList()) {
                views.add(TicketView.of(ticket));
            }
            long next = (long) offset + views.size();
            return new SearchResult(views, total, next < total ? Optional.of((int) next) : Optional.empty());
        });
    }

    @Override
    public Optional<TicketView> find(long id) {
        return read(em -> Optional.ofNullable(em.find(Ticket.class, id)).map(TicketView::of));
    }

    @Override
    public TicketView create(String title, String description, Priority priority, String createdBy) {
        return write(em -> {
            Ticket ticket = new Ticket(title, description, priority, createdBy);
            em.persist(ticket);
            return TicketView.of(ticket);
        });
    }

    @Override
    public Optional<TicketView> close(long id, String resolution) {
        return write(em -> {
            Ticket ticket = em.find(Ticket.class, id);
            if (ticket == null) {
                return Optional.<TicketView>empty();
            }
            if (ticket.getStatus() == Status.OPEN) {
                ticket.close(resolution);
            }
            return Optional.of(TicketView.of(ticket));
        });
    }

    @Override
    public Stats stats() {
        return read(em -> {
            Map<String, Long> open = new LinkedHashMap<>();
            for (Priority priority : Priority.values()) {
                open.put(priority.name(), em.createQuery("select count(t) from Ticket t where t.status = :status and t.priority = :priority", Long.class).setParameter("status", Status.OPEN).setParameter("priority", priority).getSingleResult());
            }
            long closed = em.createQuery("select count(t) from Ticket t where t.status = :status", Long.class).setParameter("status", Status.CLOSED).getSingleResult();
            long openTotal = open.values().stream().mapToLong(Long::longValue).sum();
            return new Stats(openTotal, closed, open);
        });
    }

    /** Scans at most {@link #MAX_ID_SCAN} ids in Java instead of casting in JPQL, which keeps the query portable across JPA providers. */
    @Override
    public List<Long> idsStartingWith(String prefix, int limit) {
        return read(em -> {
            List<Long> ids = em.createQuery("select t.id from Ticket t order by t.id", Long.class).setMaxResults(MAX_ID_SCAN).getResultList();
            List<Long> matches = new ArrayList<>();
            for (Long id : ids) {
                if (String.valueOf(id).startsWith(prefix)) {
                    matches.add(id);
                    if (matches.size() >= limit) {
                        break;
                    }
                }
            }
            return matches;
        });
    }

    @Override
    public long count() {
        return read(em -> em.createQuery("select count(t) from Ticket t", Long.class).getSingleResult());
    }

    private <T> T read(Function<EntityManager, T> work) {
        EntityManager em = factory.createEntityManager();
        try {
            return work.apply(em);
        } finally {
            em.close();
        }
    }

    private <T> T write(Function<EntityManager, T> work) {
        EntityManager em = factory.createEntityManager();
        try {
            em.getTransaction().begin();
            try {
                T result = work.apply(em);
                em.getTransaction().commit();
                return result;
            } catch (RuntimeException e) {
                if (em.getTransaction().isActive()) {
                    em.getTransaction().rollback();
                }
                throw e;
            }
        } finally {
            em.close();
        }
    }
}
