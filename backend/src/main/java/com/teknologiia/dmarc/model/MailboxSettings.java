package com.teknologiia.dmarc.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * The mailbox one organization collects its DMARC reports from.
 *
 * <p>Per organization, not per server. A single shared mailbox meant whichever
 * tenant pressed the button took ownership of everything in it — and an aggregate
 * report names every IP address sending as the domains it covers, so that was one
 * organization reading another's traffic.
 */
@Entity
@Table(name = "mailbox_settings")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class MailboxSettings {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false, unique = true)
    private Organization organization;

    @Column(nullable = false, length = 255)
    private String host;

    @Column(nullable = false)
    @Builder.Default
    private int port = 993;

    @Column(nullable = false, length = 255)
    private String username;

    /**
     * Encrypted, never hashed: the server has to present it to the IMAP host on
     * every run. {@link com.teknologiia.dmarc.security.SecretCipher} holds the key.
     */
    @Column(name = "password_cipher", nullable = false, length = 1024)
    private String passwordCipher;

    @Column(name = "use_ssl", nullable = false)
    @Builder.Default
    private boolean useSsl = true;

    /** Whether the scheduled collector should include this mailbox. */
    @Column(name = "polling_enabled", nullable = false)
    @Builder.Default
    private boolean pollingEnabled = true;

    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    /** Outcome of the last run, shown so a silent failure cannot look like silence. */
    @Column(name = "last_run_summary", length = 500)
    private String lastRunSummary;

    @Column(name = "last_run_ok")
    private Boolean lastRunOk;
}
