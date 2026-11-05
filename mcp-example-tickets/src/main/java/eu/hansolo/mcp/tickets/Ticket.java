package eu.hansolo.mcp.tickets;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

import java.time.Instant;

/** The persistent ticket. Plain JPA: nothing in here knows about MCP. */
@Entity
@Table(name = "tickets")
public class Ticket {

    // A sequence rather than IDENTITY: EclipseLink writes "BIGINT IDENTITY", which H2 2.x rejects, while sequences work with every provider.
    @Id
    @SequenceGenerator(name = "ticket_seq", sequenceName = "ticket_seq", allocationSize = 1)
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "ticket_seq")
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 4000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Priority priority;

    @Column(name = "created_by", nullable = false, length = 200)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(length = 1000)
    private String resolution;


    /** For JPA. */
    protected Ticket() {
    }

    public Ticket(String title, String description, Priority priority, String createdBy) {
        this.title       = title;
        this.description = description;
        this.priority    = priority;
        this.createdBy   = createdBy;
        this.status      = Status.OPEN;
        this.createdAt   = Instant.now();
    }


    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public Status getStatus() {
        return status;
    }

    public Priority getPriority() {
        return priority;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getResolution() {
        return resolution;
    }

    public void close(String resolution) {
        this.status     = Status.CLOSED;
        this.resolution = resolution;
    }
}
