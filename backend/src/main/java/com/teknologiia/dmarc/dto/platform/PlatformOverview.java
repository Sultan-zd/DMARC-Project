package com.teknologiia.dmarc.dto.platform;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * The state of the whole service, for whoever runs it.
 *
 * <p>Counts and health only. Nothing here reaches into what a tenant stores: an
 * operator can see that an organization holds four hundred reports, never what is
 * in them. A DMARC aggregate report names every IP sending as a domain, and the
 * separation between tenants is the product's main promise.
 *
 * @param signupsByDay      accounts created per day, oldest first, for a trend line
 * @param busiestTenants    name and volume only — never their contents
 * @param mailboxesFailing  mailboxes whose last collection failed, the one number
 *                          that means somebody should act today
 */
public record PlatformOverview(
        long organizations,
        long organizationsNewThisWeek,
        long accountsTotal,
        long accountsActive,
        long accountsWithTwoFactor,
        Map<String, Long> accountsByRole,
        List<DayCount> signupsByDay,
        long reportsStored,
        long recordsStored,
        long messagesCovered,
        LocalDateTime newestReportAt,
        long analysesRun,
        long publicScans,
        long invitationsPending,
        long mailboxesConfigured,
        long mailboxesPolling,
        long mailboxesFailing,
        List<TenantSummary> busiestTenants,
        RuntimeFacts runtime
) {
    public record DayCount(String day, long count) {}

    /**
     * @param id the organization's own identifier. Two tenants can register the
     *           same company name, so the name alone does not tell them apart —
     *           on screen or as a list key.
     */
    public record TenantSummary(long id, String name, long accounts, long reports,
                                long analyses, LocalDateTime createdAt) {}

    /**
     * @param databaseSizeMb total size of this application's schema
     * @param heapUsedMb     JVM heap in use — the number that precedes an outage
     */
    public record RuntimeFacts(
            String version,
            String javaVersion,
            String database,
            long uptimeMinutes,
            double databaseSizeMb,
            long heapUsedMb,
            long heapMaxMb,
            String publicUrl,
            boolean mailConfigured,
            boolean secretsKeyConfigured,
            boolean jwtSecretConfigured,
            boolean schemaAutoUpdate
    ) {}
}
