package com.teknologiia.dmarc.service;

import com.teknologiia.dmarc.dto.platform.PlatformOverview;
import com.teknologiia.dmarc.dto.platform.TenantDetail;
import com.teknologiia.dmarc.model.Organization;
import com.teknologiia.dmarc.model.User;
import com.teknologiia.dmarc.repository.*;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The service seen from above.
 *
 * <p>Every query here is deliberately unscoped, which is the opposite of the rule
 * everywhere else in this codebase. That is the point of the class and the reason
 * it is the only one allowed to do it: an operator needs to know that forty
 * organizations exist and that two of their mailboxes are failing. What none of it
 * does is read a tenant's data — no report contents, no analysis results, no
 * recipients. Counts, dates and health.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PlatformService {

    /** Days of sign-up history on the trend line. */
    private static final int TREND_DAYS = 14;

    /** How many organizations to name in the volume table. */
    private static final int BUSIEST_SHOWN = 8;

    private static final long MB = 1024 * 1024;

    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final DmarcReportRepository reportRepository;
    private final DmarcRecordRepository recordRepository;
    private final DomainAnalysisRepository analysisRepository;
    private final InvitationRepository invitationRepository;
    private final MailboxSettingsRepository mailboxRepository;
    private final OrganizationDomainRepository organizationDomainRepository;
    private final EntityManager entityManager;
    private final DataSource dataSource;
    private final AuditService auditService;
    private final SessionService sessionService;
    private final Optional<BuildProperties> buildProperties;

    @Value("${app.public-url}")
    private String publicUrl;

    @Value("${spring.mail.host:}")
    private String mailHost;

    @Value("${app.secrets.key:}")
    private String secretsKey;

    @Value("${app.jwt.secret:}")
    private String jwtSecret;

    @Value("${spring.jpa.hibernate.ddl-auto:validate}")
    private String ddlAuto;

    @Transactional(readOnly = true)
    public PlatformOverview overview() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<User> accounts = userRepository.findAll();
        List<Organization> organizations = organizationRepository.findAll();

        Map<String, Long> byRole = accounts.stream()
                .filter(User::isActive)
                .collect(Collectors.groupingBy(
                        u -> u.getRole().toUpperCase(Locale.ROOT), Collectors.counting()));

        var mailboxes = mailboxRepository.findAll();

        return new PlatformOverview(
                organizations.size(),
                organizations.stream()
                        .filter(o -> o.getCreatedAt() != null && o.getCreatedAt().isAfter(now.minusDays(7)))
                        .count(),
                accounts.size(),
                accounts.stream().filter(User::isActive).count(),
                accounts.stream().filter(u -> u.getTotpEnabledAt() != null).count(),
                byRole,
                signupsByDay(accounts, now),
                reportRepository.count(),
                recordRepository.count(),
                totalMessages(),
                newestReportAt(),
                analysisRepository.count(),
                publicScanCount(),
                pendingInvitations(now),
                mailboxes.size(),
                mailboxes.stream().filter(m -> m.isPollingEnabled()).count(),
                // The one figure that means somebody should act today.
                mailboxes.stream().filter(m -> Boolean.FALSE.equals(m.getLastRunOk())).count(),
                busiestTenants(organizations),
                runtime());
    }

    /** Every organization, newest first, with its accounts and what it holds. */
    @Transactional(readOnly = true)
    public List<TenantDetail> tenants() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return organizationRepository.findAll().stream()
                .sorted(Comparator.comparing(
                        Organization::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(o -> detail(o, now))
                .toList();
    }

    @Transactional(readOnly = true)
    public TenantDetail tenant(Long organizationId) {
        Organization organization = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No such organization."));
        return detail(organization, LocalDateTime.now(ZoneOffset.UTC));
    }

    private TenantDetail detail(Organization o, LocalDateTime now) {
        Long id = o.getId();

        List<TenantDetail.TenantAccount> accounts =
                userRepository.findByOrganizationIdOrderByCreatedAtAsc(id).stream()
                        .map(u -> new TenantDetail.TenantAccount(
                                u.getId(), u.getUsername(), u.getEmail(), u.getRole(),
                                u.isActive(), u.getTotpEnabledAt() != null,
                                Boolean.TRUE.equals(u.getMustChangePassword()), u.getCreatedAt()))
                        .toList();

        List<TenantDetail.TenantDomain> domains =
                organizationDomainRepository.findByOrganizationIdOrderByCreatedAtAsc(id).stream()
                        .map(d -> new TenantDetail.TenantDomain(
                                d.getDomain(), d.getVerifiedAt() != null, d.getDefaultRole()))
                        .toList();

        long reports = reportRepository.countByOrganizationId(id);
        long analyses = analysisRepository.countByOrganizationId(id);
        Long messages = recordRepository.sumTotalEmails(id, null, null, null);

        var mailbox = mailboxRepository.findByOrganizationId(id);
        long pending = invitationRepository.findByOrganizationIdOrderByCreatedAtDesc(id).stream()
                .filter(i -> i.getAcceptedAt() == null && i.getExpiresAt().isAfter(now))
                .count();

        return new TenantDetail(
                id, o.getName(), o.getCreatedAt(),
                reports, analyses, messages == null ? 0L : messages,
                mailbox.map(m -> m.getUsername()).orElse(null),
                mailbox.map(m -> m.getLastRunOk()).orElse(null),
                mailbox.map(m -> m.getLastRunAt()).orElse(null),
                accounts, domains, pending,
                // Safe to delete only when nothing and nobody would be lost with it.
                accounts.isEmpty() && reports == 0 && analyses == 0);
    }

    /**
     * Enables or disables one account, anywhere on the platform.
     *
     * <p>The operator's own account is excluded: locking yourself out of the only
     * console that could unlock you is not a state worth being able to reach.
     */
    @Transactional
    public void setAccountActive(Long userId, boolean active, String callerUsername) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such account."));

        if (user.getUsername().equalsIgnoreCase(callerUsername)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "You cannot disable your own account.");
        }

        user.setActive(active);
        userRepository.save(user);

        // Same reasoning as the per-organization version: disabling has to reach
        // the sessions already open, or it means nothing until the token expires.
        if (!active) {
            sessionService.revokeAll(user, callerUsername, "account disabled by an operator");
        }
        auditService.record(callerUsername, null,
                active ? AuditAction.ACCOUNT_ENABLED : AuditAction.ACCOUNT_DISABLED,
                AuditAction.TARGET_ACCOUNT, user.getId(), user.getUsername(),
                "from the platform console");

        log.warn("Platform operator {} {} account '{}'", callerUsername,
                active ? "enabled" : "disabled", user.getUsername());
    }

    /**
     * Ends every session on an account, leaving the account itself alone.
     *
     * @return the account's username, so the caller can name it back
     */
    @Transactional
    public String revokeSessions(Long userId, String callerUsername) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such account."));

        sessionService.revokeAll(user, callerUsername, "signed out by an operator");
        return user.getUsername();
    }

    /**
     * Removes an organization that holds nothing.
     *
     * <p>Refused the moment it has an account, a report or an analysis. Sign-ups
     * that were abandoned halfway leave empty shells behind, and clearing those is
     * the whole purpose; deleting a real tenant's work is not something a console
     * should make easy.
     */
    @Transactional
    public void removeEmptyOrganization(Long organizationId, String callerUsername) {
        Organization organization = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No such organization."));

        TenantDetail detail = detail(organization, LocalDateTime.now(ZoneOffset.UTC));
        if (!detail.removable()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This organization still holds accounts or data. Only empty ones can be removed.");
        }

        auditService.record(callerUsername, null, AuditAction.ORGANIZATION_REMOVED,
                AuditAction.TARGET_ORGANIZATION, organization.getId(), organization.getName(),
                "empty organization");

        organizationRepository.delete(organization);
        log.warn("Platform operator {} removed empty organization '{}'",
                callerUsername, organization.getName());
    }

    /** Accounts created per day, zero-filled so a quiet day reads as zero, not a gap. */
    private List<PlatformOverview.DayCount> signupsByDay(List<User> accounts, LocalDateTime now) {
        Map<LocalDate, Long> counted = accounts.stream()
                .filter(u -> u.getCreatedAt() != null)
                .filter(u -> u.getCreatedAt().isAfter(now.minusDays(TREND_DAYS)))
                .collect(Collectors.groupingBy(u -> u.getCreatedAt().toLocalDate(), Collectors.counting()));

        List<PlatformOverview.DayCount> days = new ArrayList<>(TREND_DAYS);
        for (int i = TREND_DAYS - 1; i >= 0; i--) {
            LocalDate day = now.toLocalDate().minusDays(i);
            days.add(new PlatformOverview.DayCount(day.toString(), counted.getOrDefault(day, 0L)));
        }
        return days;
    }

    private List<PlatformOverview.TenantSummary> busiestTenants(List<Organization> organizations) {
        return organizations.stream()
                .map(o -> new PlatformOverview.TenantSummary(
                        o.getId(),
                        o.getName(),
                        userRepository.findByOrganizationIdOrderByCreatedAtAsc(o.getId()).size(),
                        reportRepository.countByOrganizationId(o.getId()),
                        analysisRepository.countByOrganizationId(o.getId()),
                        o.getCreatedAt()))
                .sorted(Comparator.comparingLong(PlatformOverview.TenantSummary::reports).reversed()
                        .thenComparing(PlatformOverview.TenantSummary::accounts, Comparator.reverseOrder()))
                .limit(BUSIEST_SHOWN)
                .toList();
    }

    private long totalMessages() {
        Long sum = entityManager
                .createQuery("SELECT SUM(r.count) FROM DmarcRecord r", Long.class)
                .getSingleResult();
        return sum == null ? 0L : sum;
    }

    private LocalDateTime newestReportAt() {
        return entityManager
                .createQuery("SELECT MAX(r.createdAt) FROM DmarcReport r", LocalDateTime.class)
                .getSingleResult();
    }

    /** Anonymous scans are the ones with no owning organization. */
    private long publicScanCount() {
        Long count = entityManager
                .createQuery("SELECT COUNT(a) FROM DomainAnalysis a WHERE a.organization IS NULL", Long.class)
                .getSingleResult();
        return count == null ? 0L : count;
    }

    private long pendingInvitations(LocalDateTime now) {
        Long count = entityManager.createQuery(
                        "SELECT COUNT(i) FROM Invitation i WHERE i.acceptedAt IS NULL AND i.expiresAt > :now",
                        Long.class)
                .setParameter("now", now)
                .getSingleResult();
        return count == null ? 0L : count;
    }

    private PlatformOverview.RuntimeFacts runtime() {
        Runtime jvm = Runtime.getRuntime();
        long uptimeMinutes = Duration.ofMillis(ManagementFactory.getRuntimeMXBean().getUptime()).toMinutes();

        return new PlatformOverview.RuntimeFacts(
                buildProperties.map(BuildProperties::getVersion).orElse("unknown"),
                System.getProperty("java.version"),
                databaseDescription(),
                uptimeMinutes,
                databaseSizeMb(),
                (jvm.totalMemory() - jvm.freeMemory()) / MB,
                jvm.maxMemory() / MB,
                publicUrl,
                !mailHost.isBlank(),
                !secretsKey.isBlank(),
                !jwtSecret.isBlank(),
                !"validate".equals(ddlAuto) && !"none".equals(ddlAuto));
    }

    private String databaseDescription() {
        try (Connection connection = dataSource.getConnection()) {
            var meta = connection.getMetaData();
            return meta.getDatabaseProductName() + " " + meta.getDatabaseProductVersion();
        } catch (Exception e) {
            return "unavailable";
        }
    }

    /**
     * Size of this application's own schema.
     *
     * <p>Read from {@code information_schema}, which MariaDB and MySQL both expose.
     * Anything else — H2 under test, for instance — simply reports zero rather than
     * failing the whole page.
     */
    private double databaseSizeMb() {
        try {
            Object result = entityManager.createNativeQuery("""
                    SELECT ROUND(SUM(data_length + index_length) / 1048576, 2)
                    FROM information_schema.tables WHERE table_schema = DATABASE()
                    """).getSingleResult();
            return result == null ? 0d : ((Number) result).doubleValue();
        } catch (Exception e) {
            log.debug("Database size unavailable on this engine: {}", e.getMessage());
            return 0d;
        }
    }
}
