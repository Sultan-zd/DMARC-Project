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

    /**
     * Which protocol reaches this mailbox.
     *
     * <p>Nullable on purpose: rows written before Microsoft Graph support existed
     * carry no value, and they all describe IMAP mailboxes. Read it through
     * {@link MailboxKind#orDefault} rather than directly.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private MailboxKind kind;

    /** IMAP host; for Graph, {@code graph.microsoft.com}, so the column stays truthful. */
    @Column(nullable = false, length = 255)
    private String host;

    @Column(nullable = false)
    @Builder.Default
    private int port = 993;

    /** IMAP user, or — for Graph — the address of the mailbox to read. */
    @Column(nullable = false, length = 255)
    private String username;

    /**
     * The one secret this mailbox needs, encrypted rather than hashed because the
     * server must present it again on every run.
     * {@link com.teknologiia.dmarc.security.SecretCipher} holds the key.
     *
     * <p>What it holds depends on {@link #kind}: an IMAP password, or the client
     * secret of the Entra ID application registration. One column rather than two,
     * because no mailbox ever needs both and a second nullable secret column would
     * only invite storing one in the wrong place.
     */
    @Column(name = "password_cipher", nullable = false, length = 1024)
    private String passwordCipher;

    /** Entra ID directory (tenant) id. Graph only. */
    @Column(name = "tenant_id", length = 100)
    private String tenantId;

    /** Application (client) id of the Entra ID registration. Graph only. */
    @Column(name = "client_id", length = 100)
    private String clientId;

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
