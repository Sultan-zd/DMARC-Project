package com.teknologiia.dmarc.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Single-use token letting someone who has lost their password prove they still
 * hold the mailbox, and set a new one.
 *
 * <p>Shaped like {@link EmailVerificationToken} because it answers the same
 * question — does this person control this address — and kept in its own table
 * because the two have different lifetimes and must not be redeemable at each
 * other's endpoints. A verification token that could set a password would turn a
 * 24-hour sign-up link into a 24-hour account takeover.
 *
 * <p>Deliberately short-lived. A confirmation link sits unread in an inbox for a
 * day without costing anything; a link that rewrites a password is worth an hour
 * and no more.
 */
@Entity
@Table(name = "password_reset_tokens",
        indexes = @Index(name = "idx_reset_token", columnList = "token"))
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class PasswordResetToken {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The secret in the emailed link.
     *
     * <p>Named {@code token} so the operator console masks it: the column list it
     * treats as credentials matches on this name, and a reset token read out of a
     * published page is a working account takeover until it expires.
     */
    @Column(nullable = false, unique = true, length = 64)
    private String token;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /** Set once redeemed; a second attempt with the same token is refused. */
    @Column(name = "used_at")
    private LocalDateTime usedAt;

    @Column(name = "created_at", updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now(ZoneOffset.UTC);

    public boolean isUsable() {
        return usedAt == null && expiresAt.isAfter(LocalDateTime.now(ZoneOffset.UTC));
    }
}
