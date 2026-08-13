package com.teknologiia.dmarc.dto.mailbox;

import com.teknologiia.dmarc.model.MailboxKind;

import java.time.LocalDateTime;

/**
 * A configured mailbox, as the dashboard sees it.
 *
 * <p>There is deliberately no secret field. Whatever the mailbox needs — an IMAP
 * password or an Entra ID client secret — is stored encrypted and is never
 * returned: an administrator who wants to change it types a new one, and one who
 * wants to read it cannot.
 *
 * @param kind      which protocol reaches this mailbox
 * @param host      IMAP host; {@code graph.microsoft.com} for a Graph mailbox
 * @param tenantId  Entra ID directory, Graph only
 * @param clientId  application registration, Graph only
 */
public record MailboxSettingsResponse(
        boolean configured,
        MailboxKind kind,
        String host,
        int port,
        String username,
        String tenantId,
        String clientId,
        boolean useSsl,
        boolean pollingEnabled,
        LocalDateTime lastRunAt,
        String lastRunSummary,
        Boolean lastRunOk,
        int pollIntervalMinutes,
        boolean serverCanStoreSecrets
) {}
