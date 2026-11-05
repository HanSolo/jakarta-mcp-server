package eu.hansolo.mcp.tickets;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.util.logging.Logger;

/** Fills the in-memory database with 120 sample tickets so there is something to search and page through. */
@ApplicationScoped
public class SeedData {

    private static final Logger LOG = Logger.getLogger(SeedData.class.getName());

    private static final String[] PROBLEMS = {"Printer jams", "VPN drops", "Laptop battery drains", "Cannot reset password", "Monitor flickers", "Email bounces", "Shared drive is slow", "Badge reader rejects card"};
    private static final String[] PLACES   = {"on the second floor", "in the Berlin office", "for the finance team", "after the update", "in meeting room 4", "for new hires"};


    void onStart(@Observes @Initialized(ApplicationScoped.class) Object event, TicketStore store) {
        if (store.count() > 0) {
            return;
        }
        Priority[] priorities = Priority.values();
        for (int i = 1; i <= 120; i++) {
            String problem = PROBLEMS[i % PROBLEMS.length];
            String place = PLACES[i % PLACES.length];
            TicketView ticket = store.create(problem + " " + place, "Sample ticket " + i + ": " + problem.toLowerCase() + " " + place + ". Please investigate.", priorities[i % priorities.length], "seed");
            if (i % 4 == 0) {
                store.close(ticket.id(), "Fixed during seeding");
            }
        }
        LOG.info("Seeded 120 sample tickets");
    }
}
