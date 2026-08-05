package com.teknologiia.dmarc.repository;

import com.teknologiia.dmarc.model.DomainAnalysis;
import com.teknologiia.dmarc.model.Organization;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Domain analyses are tenant-scoped, and anonymous public scans are a separate,
 * ownerless class of row.
 *
 * <p>These exist because the reporting isolation tests did not cover analyses, and
 * the history endpoint was in fact returning every organization's rows — including
 * the username that ran each one.
 */
@DataJpaTest
class AnalysisIsolationTest {

    @Autowired
    private DomainAnalysisRepository analysisRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    private Organization acme;
    private Organization rival;

    @BeforeEach
    void seed() {
        analysisRepository.deleteAll();
        organizationRepository.deleteAll();

        acme = organizationRepository.save(Organization.builder().name("Acme").build());
        rival = organizationRepository.save(Organization.builder().name("Rival").build());

        save(acme, "shared.test", 90, "acme_admin", 60);
        save(acme, "acme-only.test", 70, "acme_admin", 60);
        save(rival, "shared.test", 40, "rival_admin", 60);
        save(null, "shared.test", 55, "public", 60);   // an anonymous public scan
    }

    /** @param minutesAgo when the analysis ran, kept independent of the score */
    private void save(Organization organization, String domain, int score, String by, int minutesAgo) {
        analysisRepository.save(DomainAnalysis.builder()
                .organization(organization)
                .domain(domain)
                .score(score)
                .grade("X")
                .analyzedAt(LocalDateTime.now().minusMinutes(minutesAgo))
                .analyzedBy(by)
                .build());
    }

    @Test
    @DisplayName("history returns only the caller organization's analyses")
    void historyIsScoped() {
        var page = analysisRepository.findByOrganizationIdOrderByAnalyzedAtDesc(
                acme.getId(), PageRequest.of(0, 50));

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent()).extracting(DomainAnalysis::getAnalyzedBy)
                .containsOnly("acme_admin")
                .doesNotContain("rival_admin", "public");
    }

    @Test
    @DisplayName("an analysis id belonging to another organization is not fetchable")
    void cannotFetchAnotherTenantsAnalysis() {
        DomainAnalysis rivalRow = analysisRepository.findAll().stream()
                .filter(a -> "rival_admin".equals(a.getAnalyzedBy()))
                .findFirst().orElseThrow();

        assertThat(analysisRepository.findByIdAndOrganizationId(rivalRow.getId(), acme.getId()))
                .isEmpty();
        assertThat(analysisRepository.findByIdAndOrganizationId(rivalRow.getId(), rival.getId()))
                .isPresent();
    }

    @Test
    @DisplayName("the public scanner only ever reads ownerless analyses")
    void publicLookupNeverReturnsTenantRows() {
        // Three organizations hold an analysis of shared.test. The public endpoint
        // must return the anonymous one, never a tenant's — whose analyzed_by would
        // expose an internal username to any visitor.
        var found = analysisRepository
                .findFirstByDomainAndOrganizationIsNullOrderByAnalyzedAtDesc("shared.test");

        assertThat(found).isPresent();
        assertThat(found.get().getAnalyzedBy()).isEqualTo("public");
        assertThat(found.get().getOrganization()).isNull();
    }

    @Test
    @DisplayName("posture lists the latest analysis per domain, for one organization only")
    void postureIsScopedAndDeduplicated() {
        // Genuinely more recent, not merely higher-scoring.
        save(acme, "shared.test", 95, "acme_admin", 1);

        var posture = analysisRepository.findLatestPerDomain(acme.getId());

        assertThat(posture).extracting(DomainAnalysis::getDomain)
                .containsExactlyInAnyOrder("shared.test", "acme-only.test");
        assertThat(posture).extracting(DomainAnalysis::getAnalyzedBy).containsOnly("acme_admin");
        assertThat(posture).filteredOn(a -> a.getDomain().equals("shared.test"))
                .singleElement()
                .extracting(DomainAnalysis::getScore).isEqualTo(95);
    }
}
