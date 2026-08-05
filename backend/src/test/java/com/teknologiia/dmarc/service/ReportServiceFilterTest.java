package com.teknologiia.dmarc.service;

import com.teknologiia.dmarc.dto.report.PaginatedResponse;
import com.teknologiia.dmarc.dto.report.ReportListResponse;
import com.teknologiia.dmarc.model.DmarcRecord;
import com.teknologiia.dmarc.model.DmarcReport;
import com.teknologiia.dmarc.model.Organization;
import com.teknologiia.dmarc.repository.DmarcReportRepository;
import com.teknologiia.dmarc.repository.OrganizationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import(ReportService.class)
class ReportServiceFilterTest {

    @Autowired
    private ReportService reportService;

    @Autowired
    private DmarcReportRepository repository;

    @Autowired
    private OrganizationRepository organizationRepository;

    private Organization acme;
    private Organization rival;

    @BeforeEach
    void seed() {
        repository.deleteAll();
        organizationRepository.deleteAll();

        acme = organizationRepository.save(Organization.builder().name("Acme").build());
        rival = organizationRepository.save(Organization.builder().name("Rival").build());

        // Two reports match 10.0.0.1 within Acme, and both hold several matching
        // records — the shape that inflates a count query built on a join.
        save(acme, "report-a", "acme.test", "10.0.0.1", "10.0.0.1", "10.0.0.1");
        save(acme, "report-b", "acme.test", "10.0.0.1", "10.0.0.1", "8.8.8.8");
        save(acme, "report-c", "other.test", "8.8.8.8");

        // Rival's data deliberately overlaps on every filterable field.
        save(rival, "rival-a", "acme.test", "10.0.0.1", "10.0.0.1");
        save(rival, "rival-b", "other.test", "8.8.8.8");
    }

    private DmarcReport save(Organization organization, String reportId, String domain, String... sourceIps) {
        DmarcReport report = DmarcReport.builder()
                .organization(organization)
                .reportId(reportId)
                .orgName("Test Org")
                .domain(domain)
                .policy("none")
                .dateBegin(LocalDateTime.now().minusDays(1))
                .dateEnd(LocalDateTime.now())
                .build();

        List<DmarcRecord> records = new ArrayList<>();
        for (String ip : sourceIps) {
            records.add(DmarcRecord.builder()
                    .report(report).sourceIp(ip).count(10)
                    .disposition("none").dkimResult("pass").spfResult("pass")
                    .build());
        }
        report.setRecords(records);
        return repository.save(report);
    }

    private PaginatedResponse<ReportListResponse> search(Organization org, String sourceIp, int page, int size) {
        return reportService.getReports(org.getId(), null, null, sourceIp,
                null, null, null, "date_begin", "desc", page, size);
    }

    // ─── Tenant isolation ───────────────────────────────────────────

    @Nested
    @DisplayName("tenant isolation")
    class Isolation {

        @Test
        @DisplayName("never returns another organization's reports")
        void neverReturnsOtherTenantsReports() {
            PaginatedResponse<ReportListResponse> mine = search(acme, null, 1, 100);

            assertThat(mine.total()).isEqualTo(3);
            assertThat(mine.items()).extracting(ReportListResponse::report_id)
                    .containsExactlyInAnyOrder("report-a", "report-b", "report-c")
                    .doesNotContain("rival-a", "rival-b");
        }

        @Test
        @DisplayName("keeps counts separate when both tenants match the same filter")
        void countsStaySeparateUnderIdenticalFilters() {
            // Both organizations hold acme.test reports carrying 10.0.0.1.
            assertThat(search(acme, "10.0.0.1", 1, 100).total()).isEqualTo(2);
            assertThat(search(rival, "10.0.0.1", 1, 100).total()).isEqualTo(1);
        }

        @Test
        @DisplayName("refuses to fetch a report id owned by another organization")
        void cannotFetchAnotherTenantsReportById() {
            Long rivalReportId = repository.findAll().stream()
                    .filter(r -> r.getReportId().equals("rival-a"))
                    .findFirst().orElseThrow().getId();

            // Scoped lookup: the row exists, but not for this caller — so it must be
            // indistinguishable from one that does not exist.
            assertThatThrownBy(() -> reportService.getReport(acme.getId(), rivalReportId))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("404");

            assertThat(reportService.getReport(rival.getId(), rivalReportId).report_id())
                    .isEqualTo("rival-a");
        }

        @Test
        @DisplayName("lets both organizations hold the same provider report id")
        void sameReportIdAllowedInDifferentOrganisations() {
            // A provider sends the same aggregate report to two customers; neither
            // import may be rejected as a duplicate of the other's.
            save(acme, "shared-report", "shared.test", "1.1.1.1");
            save(rival, "shared-report", "shared.test", "1.1.1.1");

            assertThat(repository.existsByOrganizationIdAndReportId(acme.getId(), "shared-report")).isTrue();
            assertThat(repository.existsByOrganizationIdAndReportId(rival.getId(), "shared-report")).isTrue();
        }
    }

    // ─── Filtering and pagination ───────────────────────────────────

    @Test
    @DisplayName("counts each report once however many of its records match the source IP")
    void countsReportsNotRecords() {
        PaginatedResponse<ReportListResponse> result = search(acme, "10.0.0.1", 1, 20);

        // Five records carry 10.0.0.1 in Acme, but they belong to only two reports.
        assertThat(result.total()).isEqualTo(2);
        assertThat(result.items()).hasSize(2);
        assertThat(result.items()).extracting(ReportListResponse::report_id)
                .containsExactlyInAnyOrder("report-a", "report-b");
    }

    @Test
    @DisplayName("reports the same total whatever the page size")
    void totalIsStableAcrossPageSizes() {
        // Spring Data skips the count query when a page holds the whole result, so a
        // broken count only shows up at small page sizes. Both paths must agree.
        assertThat(search(acme, "10.0.0.1", 1, 1).total())
                .isEqualTo(search(acme, "10.0.0.1", 1, 500).total())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("paginates a filtered result set consistently")
    void paginatesFilteredResults() {
        PaginatedResponse<ReportListResponse> firstPage = search(acme, "10.0.0.1", 1, 1);

        assertThat(firstPage.total()).isEqualTo(2);
        assertThat(firstPage.total_pages()).isEqualTo(2);
        assertThat(firstPage.items()).hasSize(1);

        PaginatedResponse<ReportListResponse> secondPage = search(acme, "10.0.0.1", 2, 1);
        assertThat(secondPage.items()).hasSize(1);
        assertThat(secondPage.items().get(0).report_id())
                .isNotEqualTo(firstPage.items().get(0).report_id());
    }

    @Test
    @DisplayName("matches source IPs by prefix")
    void matchesSourceIpPrefix() {
        assertThat(search(acme, "10.0.", 1, 20).total()).isEqualTo(2);
        assertThat(search(acme, "8.8.8.8", 1, 20).total()).isEqualTo(2);
        assertThat(search(acme, "203.0.113.1", 1, 20).total()).isZero();
    }

    @Test
    @DisplayName("filters by domain within the organization only")
    void filtersByDomain() {
        PaginatedResponse<ReportListResponse> result = reportService.getReports(
                acme.getId(), "acme.test", null, null, null, null, null,
                "date_begin", "desc", 1, 20);

        // Rival also has an acme.test report; it must not be counted here.
        assertThat(result.total()).isEqualTo(2);
        assertThat(result.items()).extracting(ReportListResponse::domain).containsOnly("acme.test");
    }

    @Test
    @DisplayName("caps an oversized page size request")
    void capsPageSize() {
        assertThat(search(acme, null, 1, 100_000).page_size()).isEqualTo(200);
    }
}
