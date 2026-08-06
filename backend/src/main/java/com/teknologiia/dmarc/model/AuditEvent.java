package com.teknologiia.dmarc.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * One thing somebody did that changed something.
 *
 * <p>All of this was already written to the application log. A log file is the
 * wrong shape for the question it is asked: it rotates, so the answer to "who
 * deleted this account in March" may simply be gone; it interleaves with every
 * other line the process emits; and it cannot be filtered by actor or by target
 * without reading it by hand. For a product sold on knowing who sent mail as your
 * domain, "who removed this organization" should be a query.
 *
 * <p>Deliberately append-only in practice: nothing in the application updates a
 * row here. The operator console can still delete rows — it can delete anything —
 * but doing so is itself the sort of act this table exists to make visible, and it
 * leaves a hole in the identifiers rather than a silent gap.
 *
 * <p>What is <em>not</em> recorded: reads. Every successful sign-in and every page
 * view would swamp the mutations, and nothing purges this table yet. Failed
 * sign-ins are kept because they are rate-limited to a trickle and are the one
 * read-shaped event worth having.
 */
@Entity
@Table(name = "audit_events", indexes = {
        @Index(name = "idx_audit_at", columnList = "at"),
        @Index(name = "idx_audit_actor", columnList = "actor"),
        @Index(name = "idx_audit_action", columnList = "action")
})
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class AuditEvent {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "at", nullable = false)
    @Builder.Default
    private LocalDateTime at = LocalDateTime.now(ZoneOffset.UTC);

    /** Who did it, by username. {@code system} for anything nobody asked for. */
    @Column(nullable = false, length = 50)
    private String actor;

    /** The actor's organization, or null for an operator acting across all of them. */
    @Column(name = "actor_organization_id")
    private Long actorOrganizationId;

    /** What was done — one of {@link com.teknologiia.dmarc.service.AuditAction}. */
    @Column(nullable = false, length = 40)
    private String action;

    /** What it was done to: ACCOUNT, ORGANIZATION, TABLE, MAILBOX, SESSION. */
    @Column(name = "target_type", length = 20)
    private String targetType;

    /** The target's key, where it has one. */
    @Column(name = "target_id")
    private Long targetId;

    /**
     * The target's name at the time.
     *
     * <p>Copied rather than joined on purpose. The row this points at is often
     * exactly the row that was deleted, and an audit entry reading "account #47"
     * long after #47 stopped existing answers nothing.
     */
    @Column(name = "target_label", length = 255)
    private String targetLabel;

    /** What is worth knowing beyond the action itself. Never a credential. */
    @Column(length = 500)
    private String detail;

    /** Where it came from, as far as the deployment can tell. */
    @Column(name = "client_ip", length = 45)
    private String clientIp;
}
