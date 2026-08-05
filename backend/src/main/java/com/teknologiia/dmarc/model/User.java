package com.teknologiia.dmarc.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Entity @Table(name = "users")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class User {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 50)
    private String username;

    @Column(unique = true, length = 255)
    private String email;

    @Column(name = "hashed_password", nullable = false)
    private String hashedPassword;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String role = "analyst";

    /**
     * The tenant this account belongs to. Every read of ingested data is scoped by
     * it, so an account without one can see nothing.
     */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    /**
     * Doubles as the email-verification flag: a freshly registered account is
     * inactive until its token is redeemed, and Spring Security refuses to
     * authenticate a disabled account.
     */
    @Column(name = "is_active")
    @Builder.Default
    private boolean active = true;

    /**
     * Set when an administrator chose the password. It travelled through a channel
     * nobody controls — a message, a call — so the account is held at a change
     * screen until the user picks their own.
     */
    @Column(name = "must_change_password")
    @Builder.Default
    private Boolean mustChangePassword = false;

    /**
     * Shared secret for time-based one-time passwords.
     *
     * <p>Set the moment enrolment starts, but worthless until {@link #totpEnabledAt}
     * is also set: a secret nobody has confirmed they can read must not be able to
     * lock an account out of its own sign-in.
     */
    @Column(name = "totp_secret", length = 64)
    private String totpSecret;

    /** When the second factor was confirmed. Null means it is not in force. */
    @Column(name = "totp_enabled_at")
    private LocalDateTime totpEnabledAt;

    @Column(name = "created_at", updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now(ZoneOffset.UTC);

    /**
     * Null-safe accessor. The column was added to a populated table, so existing
     * rows hold NULL — reading that into a primitive threw during authentication,
     * which surfaced as "invalid username or password" and hid the real cause.
     */
    public boolean isMustChangePassword() {
        return Boolean.TRUE.equals(mustChangePassword);
    }
}
