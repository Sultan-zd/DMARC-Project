package com.teknologiia.dmarc.dto.admin;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * What an administrator needs to see before deciding anything: who is in, how they
 * got in, and whether data is still arriving.
 *
 * <p>Every figure is scoped to the caller's own organization.
 *
 * @param accountsByRole        active account count per role, uppercase keys
 * @param reportingWindowStart  earliest period covered by a stored report
 * @param reportingWindowEnd    latest period covered by a stored report
 * @param mailboxConfigured     whether an IMAP password is set; without one the
 *                              scheduled poll does nothing and reports must be
 *                              uploaded by hand
 */
public record AdminOverviewResponse(
        long accountsTotal,
        long accountsActive,
        Map<String, Long> accountsByRole,
        long invitationsPending,
        long domainsClaimed,
        long domainsVerified,
        long reportsStored,
        long messagesCovered,
        LocalDateTime reportingWindowStart,
        LocalDateTime reportingWindowEnd,
        long analysesRun,
        long domainsAnalysed,
        List<String> weakestDomains,
        boolean mailboxConfigured,
        String mailboxAddress,
        int mailboxPollMinutes
) {}
