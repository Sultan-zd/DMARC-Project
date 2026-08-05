package com.teknologiia.dmarc.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * An invitation to join an existing organization.
 *
 * <p>The direct answer to a colleague signing up on their own and landing in a
 * separate silo: an administrator invites the address, and accepting the link puts
 * the new account inside the existing organization with the role chosen up front.
 *
 * <p>Accepting also proves the address — the link only reaches whoever reads that
 * mailbox — so an invited account is active immediately, with no separate email
 * verification step.
 */
@Entity
@Table(name = "invitations",
        indexes = @Index(name = "idx_invitation_token", columnList = "token"))
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class Invitation {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(nullable = false, length = 255)
    private String email;

    @Column(nullable = false, length = 20)
    private String role;

    @Column(nullable = false, unique = true, length = 64)
    private String token;

    @Column(name = "invited_by", length = 50)
    private String invitedBy;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /** Set on acceptance; a second attempt with the same link is refused. */
    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;

    @Column(name = "created_at", updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now(ZoneOffset.UTC);

    public boolean isUsable() {
        return acceptedAt == null && expiresAt.isAfter(LocalDateTime.now(ZoneOffset.UTC));
    }
}
