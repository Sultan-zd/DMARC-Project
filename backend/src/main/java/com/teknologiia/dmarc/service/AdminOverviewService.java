package com.teknologiia.dmarc.service;

import com.teknologiia.dmarc.dto.admin.AdminOverviewResponse;
import com.teknologiia.dmarc.model.DomainAnalysis;
import com.teknologiia.dmarc.model.OrganizationDomain;
import com.teknologiia.dmarc.model.User;
import com.teknologiia.dmarc.repository.DmarcRecordRepository;
import com.teknologiia.dmarc.repository.DmarcReportRepository;
import com.teknologiia.dmarc.repository.DomainAnalysisRepository;
import com.teknologiia.dmarc.repository.InvitationRepository;
import com.teknologiia.dmarc.repository.OrganizationDomainRepository;
import com.teknologiia.dmarc.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The operational picture of one organization.
 *
 * <p>Read-only, and every query is scoped by organization id — an administrator of
 * one tenant must not be able to count another's accounts or reports.
 */
@Service
@RequiredArgsConstructor
public class AdminOverviewService {

    /** How many of the worst-scoring domains to name. */
    private static final int WEAKEST_SHOWN = 3;

    private final UserRepository userRepository;
    private final InvitationRepository invitationRepository;
    private final OrganizationDomainRepository domainRepository;
    private final DmarcReportRepository reportRepository;
    private final DmarcRecordRepository recordRepository;
    private final DomainAnalysisRepository analysisRepository;

    private final com.teknologiia.dmarc.repository.MailboxSettingsRepository mailboxRepository;

    @Value("${app.imap.polling-interval-minutes:15}")
    private int imapPollMinutes;

    @Transactional(readOnly = true)
    public AdminOverviewResponse overview(Long organizationId) {
        List<User> accounts = userRepository.findByOrganizationIdOrderByCreatedAtAsc(organizationId);
        long active = accounts.stream().filter(User::isActive).count();

        Map<String, Long> byRole = accounts.stream()
                .filter(User::isActive)
                .collect(Collectors.groupingBy(
                        u -> u.getRole().toUpperCase(Locale.ROOT),
                        Collectors.counting()));

        LocalDateTime now = LocalDateTime.now();
        long pending = invitationRepository.findByOrganizationIdOrderByCreatedAtDesc(organizationId)
                .stream()
                .filter(i -> i.getAcceptedAt() == null && i.getExpiresAt().isAfter(now))
                .count();

        List<OrganizationDomain> claims =
                domainRepository.findByOrganizationIdOrderByCreatedAtAsc(organizationId);
        long verified = claims.stream().filter(c -> c.getVerifiedAt() != null).count();

        Long messages = recordRepository.sumTotalEmails(organizationId, null, null, null);

        // MIN/MAX over an empty table still returns one row, of two nulls.
        List<Object[]> window = reportRepository.findReportingWindow(organizationId);
        LocalDateTime from = null;
        LocalDateTime to = null;
        if (!window.isEmpty() && window.get(0) != null) {
            from = (LocalDateTime) window.get(0)[0];
            to = (LocalDateTime) window.get(0)[1];
        }

        var mailbox = mailboxRepository.findByOrganizationId(organizationId);

        // findLatestPerDomain already returns worst-first, one row per domain.
        List<DomainAnalysis> posture = analysisRepository.findLatestPerDomain(organizationId);
        List<String> weakest = posture.stream()
                .sorted(Comparator.comparingInt(DomainAnalysis::getScore))
                .limit(WEAKEST_SHOWN)
                .map(a -> a.getDomain() + " (" + a.getScore() + ")")
                .toList();

        return new AdminOverviewResponse(
                accounts.size(),
                active,
                byRole,
                pending,
                claims.size(),
                verified,
                reportRepository.countByOrganizationId(organizationId),
                messages == null ? 0L : messages,
                from,
                to,
                analysisRepository.countByOrganizationId(organizationId),
                posture.size(),
                weakest,
                // The organization's own mailbox, not a server-wide one.
                mailbox.isPresent() && mailbox.get().isPollingEnabled(),
                mailbox.map(m -> m.getUsername()).orElse(null),
                imapPollMinutes);
    }
}
