package com.teknologiia.dmarc.service;

import com.teknologiia.dmarc.dto.admin.AdminOverviewResponse;
import com.teknologiia.dmarc.model.DmarcRecord;
import com.teknologiia.dmarc.model.DmarcReport;
import com.teknologiia.dmarc.model.DomainAnalysis;
import com.teknologiia.dmarc.model.Invitation;
import com.teknologiia.dmarc.model.Organization;
import com.teknologiia.dmarc.model.OrganizationDomain;
import com.teknologiia.dmarc.model.User;
import com.teknologiia.dmarc.repository.DmarcRecordRepository;
import com.teknologiia.dmarc.repository.DmarcReportRepository;
import com.teknologiia.dmarc.repository.DomainAnalysisRepository;
import com.teknologiia.dmarc.repository.InvitationRepository;
import com.teknologiia.dmarc.repository.OrganizationDomainRepository;
import com.teknologiia.dmarc.repository.OrganizationRepository;
import com.teknologiia.dmarc.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The administration overview: counted for one organization, and nobody else.
 *
 * <p>Runs against the real repositories because the figures come from queries, not
 * from logic — a scoping mistake would only ever show up here.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.imap.polling-interval-minutes=20",
        "app.secrets.key=c2l4dGVlbi1ieXRlcy10aW1lcy10d28tZXhhY3RseS4=",
})
class AdminOverviewServiceTest {

    @Autowired private AdminOverviewService service;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private InvitationRepository invitationRepository;
    @Autowired private OrganizationDomainRepository domainRepository;
    @Autowired private DmarcReportRepository reportRepository;
    @Autowired private DmarcRecordRepository recordRepository;
    @Autowired private DomainAnalysisRepository analysisRepository;
    @Autowired private com.teknologiia.dmarc.repository.MailboxSettingsRepository mailboxRepository;

    private Organization acme;
    private Organization rival;

    @BeforeEach
    void seed() {
        mailboxRepository.deleteAll();
        recordRepository.deleteAll();
        reportRepository.deleteAll();
        analysisRepository.deleteAll();
        invitationRepository.deleteAll();
        domainRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();

        acme = organizationRepository.save(Organization.builder().name("Acme").build());
        rival = organizationRepository.save(Organization.builder().name("Rival").build());

        user(acme, "acme_admin", "ADMIN", true);
        user(acme, "acme_analyst", "ANALYST", true);
        user(acme, "acme_gone", "VIEWER", false);
        user(rival, "rival_admin", "ADMIN", true);

        LocalDateTime now = LocalDateTime.now();
        invitation(acme, "pending@acme.test", now.plusDays(3), null);
        invitation(acme, "expired@acme.test", now.minusDays(1), null);
        invitation(acme, "joined@acme.test", now.plusDays(3), now.minusHours(2));
        invitation(rival, "other@rival.test", now.plusDays(3), null);

        claim(acme, "acme.test", now.minusDays(5));
        claim(acme, "acme-second.test", null);
        claim(rival, "rival.test", now.minusDays(5));

        report(acme, "r1", "acme.test", now.minusDays(9), now.minusDays(8), 120);
        report(acme, "r2", "acme.test", now.minusDays(3), now.minusDays(2), 80);
        report(rival, "r3", "rival.test", now.minusDays(30), now.minusDays(29), 999);

        analysis(acme, "weak.test", 42, now.minusHours(3));
        analysis(acme, "strong.test", 96, now.minusHours(2));
        analysis(acme, "weak.test", 55, now.minusHours(1));   // re-checked, improved
        analysis(rival, "rival.test", 10, now.minusHours(1));
    }

    private void user(Organization organization, String name, String role, boolean active) {
        userRepository.save(User.builder()
                .username(name).email(name + "@test").hashedPassword("x")
                .role(role).active(active).organization(organization).build());
    }

    private void invitation(Organization organization, String email,
                            LocalDateTime expiresAt, LocalDateTime acceptedAt) {
        invitationRepository.save(Invitation.builder()
                .organization(organization).email(email).role("VIEWER")
                .token(email + "-token").invitedBy("acme_admin")
                .expiresAt(expiresAt).acceptedAt(acceptedAt).build());
    }

    private void claim(Organization organization, String domain, LocalDateTime verifiedAt) {
        domainRepository.save(OrganizationDomain.builder()
                .organization(organization).domain(domain)
                .verificationToken("token-" + domain).verifiedAt(verifiedAt)
                .defaultRole("VIEWER").build());
    }

    private void report(Organization organization, String reportId, String domain,
                        LocalDateTime from, LocalDateTime to, int messages) {
        DmarcReport saved = reportRepository.save(DmarcReport.builder()
                .organization(organization).reportId(reportId).orgName("provider")
                .domain(domain).dateBegin(from).dateEnd(to).policy("reject").build());
        recordRepository.save(DmarcRecord.builder()
                .report(saved).sourceIp("192.0.2.1").count(messages)
                .disposition("none").dkimResult("pass").spfResult("pass").build());
    }

    private void analysis(Organization organization, String domain, int score, LocalDateTime at) {
        analysisRepository.save(DomainAnalysis.builder()
                .organization(organization).domain(domain).score(score)
                .grade("X").analyzedBy("acme_admin").analyzedAt(at).build());
    }

    @Test
    @DisplayName("counts accounts by role, ignoring disabled ones")
    void countsActiveAccountsByRole() {
        AdminOverviewResponse overview = service.overview(acme.getId());

        assertThat(overview.accountsTotal()).isEqualTo(3);
        assertThat(overview.accountsActive()).isEqualTo(2);
        assertThat(overview.accountsByRole())
                .containsOnlyKeys("ADMIN", "ANALYST")
                .containsEntry("ADMIN", 1L)
                .containsEntry("ANALYST", 1L);
    }

    @Test
    @DisplayName("only invitations that can still be accepted count as pending")
    void countsOnlyUsableInvitations() {
        // Three invitations exist, but one has expired and one has been used.
        assertThat(service.overview(acme.getId()).invitationsPending()).isEqualTo(1);
    }

    @Test
    @DisplayName("separates verified claims from merely created ones")
    void separatesVerifiedClaims() {
        AdminOverviewResponse overview = service.overview(acme.getId());

        assertThat(overview.domainsClaimed()).isEqualTo(2);
        assertThat(overview.domainsVerified()).isEqualTo(1);
    }

    @Test
    @DisplayName("reports the stored volume and the window it covers")
    void reportsVolumeAndWindow() {
        AdminOverviewResponse overview = service.overview(acme.getId());

        assertThat(overview.reportsStored()).isEqualTo(2);
        assertThat(overview.messagesCovered()).isEqualTo(200);

        // The window spans the earliest start to the latest end across every report,
        // not the bounds of any single one.
        assertThat(overview.reportingWindowStart())
                .isAfter(LocalDateTime.now().minusDays(10))
                .isBefore(LocalDateTime.now().minusDays(8));
        assertThat(overview.reportingWindowEnd())
                .isAfter(LocalDateTime.now().minusDays(3))
                .isBefore(LocalDateTime.now());
    }

    @Test
    @DisplayName("names the weakest domains using each domain's newest analysis")
    void namesWeakestDomainsFromLatestAnalysis() {
        AdminOverviewResponse overview = service.overview(acme.getId());

        assertThat(overview.analysesRun()).isEqualTo(3);
        // Two domains, though three analyses ran: weak.test was checked twice.
        assertThat(overview.domainsAnalysed()).isEqualTo(2);
        // 55, not the 42 it scored first time round.
        assertThat(overview.weakestDomains()).containsExactly("weak.test (55)", "strong.test (96)");
    }

    @Test
    @DisplayName("an organization with no mailbox of its own reports none")
    void mailboxIsPerOrganization() {
        AdminOverviewResponse overview = service.overview(acme.getId());

        // The mailbox used to come from server-wide configuration, so every tenant
        // saw the same address whether or not it was theirs.
        assertThat(overview.mailboxConfigured()).isFalse();
        assertThat(overview.mailboxAddress()).isNull();
        assertThat(overview.mailboxPollMinutes()).isEqualTo(20);
    }

    @Test
    @DisplayName("a configured mailbox is reported to its own organization only")
    void configuredMailboxIsScoped(@Autowired MailboxSettingsService mailboxService) {
        mailboxService.save(acme.getId(), new com.teknologiia.dmarc.dto.mailbox.MailboxSettingsRequest(
                "imap.acme.test", 993, "reports@acme.test", "secret", true, true));

        assertThat(service.overview(acme.getId()).mailboxAddress()).isEqualTo("reports@acme.test");
        assertThat(service.overview(acme.getId()).mailboxConfigured()).isTrue();

        // The other tenant sees nothing of it.
        assertThat(service.overview(rival.getId()).mailboxAddress()).isNull();
        assertThat(service.overview(rival.getId()).mailboxConfigured()).isFalse();
    }

    @Test
    @DisplayName("counts nothing belonging to another organization")
    void countsAreScopedToOneOrganization() {
        AdminOverviewResponse acmeView = service.overview(acme.getId());
        AdminOverviewResponse rivalView = service.overview(rival.getId());

        assertThat(rivalView.accountsTotal()).isEqualTo(1);
        assertThat(rivalView.reportsStored()).isEqualTo(1);
        assertThat(rivalView.messagesCovered()).isEqualTo(999);
        assertThat(rivalView.invitationsPending()).isEqualTo(1);
        assertThat(rivalView.domainsClaimed()).isEqualTo(1);
        assertThat(rivalView.weakestDomains()).containsExactly("rival.test (10)");

        // Neither view has picked up anything from the other.
        assertThat(acmeView.messagesCovered()).isEqualTo(200);
        assertThat(acmeView.weakestDomains()).noneMatch(d -> d.contains("rival"));
    }

    @Test
    @DisplayName("an organization with nothing in it reports zeroes, not failures")
    void emptyOrganizationReportsZeroes() {
        Organization fresh = organizationRepository.save(
                Organization.builder().name("Fresh").build());

        AdminOverviewResponse overview = service.overview(fresh.getId());

        assertThat(overview.accountsTotal()).isZero();
        assertThat(overview.reportsStored()).isZero();
        // MIN/MAX over no rows still returns a row, of nulls.
        assertThat(overview.reportingWindowStart()).isNull();
        assertThat(overview.reportingWindowEnd()).isNull();
        assertThat(overview.messagesCovered()).isZero();
        assertThat(overview.weakestDomains()).isEmpty();
    }

    @Test
    @DisplayName("the published system facts come from the running process")
    void systemFactsAreRead(@Autowired SystemInfoService systemInfoService) {
        var info = systemInfoService.info();

        assertThat(info.javaVersion()).isEqualTo(System.getProperty("java.version"));
        assertThat(info.database()).isNotBlank().isNotEqualTo("unavailable");
        assertThat(info.dnsResolver()).isEqualTo(ScoringModel.published().resolver());
        assertThat(info.sessionMinutes()).isPositive();

        // The pre-publication checks read the configuration this build is running
        // under, so they must reflect the test profile rather than a fixed answer.
        assertThat(info.jwtSecretProvided()).isTrue();
        assertThat(info.schemaAutoUpdate()).isTrue();   // create-drop, in tests
    }
}
