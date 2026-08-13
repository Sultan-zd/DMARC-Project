package com.teknologiia.dmarc.model;

/**
 * How the application reaches an organization's report mailbox.
 *
 * <p>Two kinds exist because the two large providers no longer agree on how a
 * background service should authenticate.
 *
 * <p>Google still accepts an <em>app password</em> over IMAP: a credential the
 * user generates, hands to the application, and can revoke. That is
 * {@link #IMAP}, and it also covers every self-hosted mail server.
 *
 * <p>Microsoft removed Basic authentication from Exchange Online for IMAP, POP,
 * EWS and ActiveSync. No password — app password or otherwise — will open an
 * IMAP session against a Microsoft 365 mailbox any more; the credential is
 * refused before the mailbox is ever reached. Collecting from Microsoft
 * therefore needs {@link #MICROSOFT_GRAPH}, which authenticates as a registered
 * application against Entra ID and reads the mailbox over HTTPS.
 *
 * <p>The distinction is not a preference. A deployment whose reports arrive at a
 * Microsoft 365 address cannot use IMAP at all, and one whose reports arrive at
 * Gmail cannot use Graph.
 */
public enum MailboxKind {

    /** Classic IMAP with a username and password. Gmail, and self-hosted servers. */
    IMAP,

    /** Microsoft 365, read through the Graph API as a registered application. */
    MICROSOFT_GRAPH;

    /**
     * Rows written before this column existed describe IMAP mailboxes, so a null
     * reads as IMAP rather than failing.
     */
    public static MailboxKind orDefault(MailboxKind kind) {
        return kind == null ? IMAP : kind;
    }
}
