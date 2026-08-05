package com.teknologiia.dmarc.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Single-use token proving an address belongs to whoever registered with it.
 *
 * <p>The account stays disabled until the token is redeemed, so an address typed by
 * mistake — or on purpose, to sign somebody else up — never yields a usable login.
 */
@Entity
@Table(name = "email_verification_tokens",
        indexes = @Index(name = "idx_verification_token", columnList = "token"))
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class EmailVerificationToken {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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
