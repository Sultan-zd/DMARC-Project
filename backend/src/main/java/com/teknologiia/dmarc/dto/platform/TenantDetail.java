package com.teknologiia.dmarc.dto.platform;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One organization, as the operator sees it.
 *
 * <p>Its accounts in full — who they are, what they may do, whether they are
 * protected — and the volume it holds. Not what that volume contains: the reports
 * themselves stay with the tenant, which is what the landing page tells anyone
 * signing up.
 *
 * @param removable whether this organization can be deleted, meaning it holds
 *                  nothing anybody would miss
 */
public record TenantDetail(
        long id,
        String name,
        LocalDateTime createdAt,
        long reports,
        long analyses,
        long messagesCovered,
        String mailboxAddress,
        Boolean mailboxLastRunOk,
        LocalDateTime mailboxLastRunAt,
        List<TenantAccount> accounts,
        List<TenantDomain> claimedDomains,
        long invitationsPending,
        boolean removable
) {
    public record TenantAccount(
            long id,
            String username,
            String email,
            String role,
            boolean active,
            boolean twoFactorEnabled,
            boolean mustChangePassword,
            LocalDateTime createdAt
    ) {}

    public record TenantDomain(String domain, boolean verified, String defaultRole) {}
}
