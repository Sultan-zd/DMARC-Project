package com.teknologiia.dmarc.dto.mailbox;

import java.time.LocalDateTime;

/**
 * A configured mailbox, as the dashboard sees it.
 *
 * <p>There is deliberately no password field. It is stored encrypted and is never
 * returned — an administrator who wants to change it types a new one, and one who
 * wants to read it cannot.
 */
public record MailboxSettingsResponse(
        boolean configured,
        String host,
        int port,
        String username,
        boolean useSsl,
        boolean pollingEnabled,
        LocalDateTime lastRunAt,
        String lastRunSummary,
        Boolean lastRunOk,
        int pollIntervalMinutes,
        boolean serverCanStoreSecrets
) {}
