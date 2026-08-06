package com.teknologiia.dmarc.service;

import com.teknologiia.dmarc.dto.report.PaginatedResponse;
import com.teknologiia.dmarc.dto.report.RecordResponse;
import com.teknologiia.dmarc.dto.report.ReportDetailResponse;
import com.teknologiia.dmarc.dto.report.ReportListResponse;
import com.teknologiia.dmarc.model.DmarcRecord;
import com.teknologiia.dmarc.model.DmarcReport;
import com.teknologiia.dmarc.repository.DmarcReportRepository;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportService {

    /** Caps how much a single request can pull, regardless of what the client asks for. */
    private static final int MAX_PAGE_SIZE = 200;

    /**
     * Sortable columns, keyed by the snake_case name the API exposes. Restricting
     * sorting to a known set keeps client input out of the generated query.
     */
    private static final Map<String, String> SORTABLE = Map.of(
            "date_begin", "dateBegin",
            "date_end", "dateEnd",
            "created_at", "createdAt",
            "org_name", "orgName",
            "domain", "domain",
            "report_id", "reportId",
            "policy", "policy"
    );

    private final DmarcReportRepository reportRepository;

    public PaginatedResponse<ReportListResponse> getReports(
            Long organizationId,
            String domain, String orgName, String sourceIp,
            LocalDateTime dateFrom, LocalDateTime dateTo, String policy,
            String sortBy, String sortOrder, int page, int size) {

        int pageNumber = Math.max(page, 1);
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        Sort.Direction direction = "asc".equalsIgnoreCase(sortOrder) ? Sort.Direction.ASC : Sort.Direction.DESC;
        String sortField = SORTABLE.getOrDefault(sortBy, "dateBegin");

        // PageRequest is zero-based; the API is one-based.
        //
        // The key is broken by id, and that is not cosmetic. Two reports covering
        // the same period sort equally, and a database is free to return equal rows
        // in any order it likes — including a different one per query. Paginating on
        // a key that is not a total order lets the same report appear on two pages
        // while another is never shown at all, with nothing in the output admitting
        // it. The tie-break makes the sequence reproducible.
        Pageable pageable = PageRequest.of(pageNumber - 1, pageSize,
                Sort.by(direction, sortField).and(Sort.by(Sort.Direction.DESC, "id")));

        Specification<DmarcReport> spec =
                filter(organizationId, domain, orgName, sourceIp, dateFrom, dateTo, policy);
        Page<DmarcReport> results = reportRepository.findAll(spec, pageable);

        List<ReportListResponse> items = results.getContent().stream()
                .map(report -> new ReportListResponse(
                        report.getId(), report.getReportId(), report.getOrgName(),
                        report.getDateBegin(), report.getDateEnd(), report.getDomain(), report.getPolicy(),
                        report.getRecords().size(),
                        report.getRecords().stream().mapToInt(DmarcRecord::getCount).sum(),
                        report.getCreatedAt()))
                .toList();

        return new PaginatedResponse<>(
                items, results.getTotalElements(), pageNumber, pageSize, results.getTotalPages());
    }

    /**
     * Fetches one report. The lookup is scoped to the caller's organization, so a
     * report belonging to another tenant is indistinguishable from one that does
     * not exist — no existence oracle.
     */
    public ReportDetailResponse getReport(Long organizationId, Long id) {
        DmarcReport report = reportRepository.findByIdAndOrganizationId(id, organizationId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No DMARC report with id " + id));

        List<RecordResponse> records = report.getRecords().stream()
                .map(record -> new RecordResponse(
                        record.getId(), record.getSourceIp(), record.getCount(), record.getDisposition(),
                        record.getDkimResult(), record.getSpfResult(), record.getDkimDomain(),
                        record.getSpfDomain(), record.getHeaderFrom(), record.getEnvelopeFrom(),
                        record.getDkimSelector()))
                .toList();

        return new ReportDetailResponse(
                report.getId(), report.getReportId(), report.getOrgName(), report.getOrgEmail(),
                report.getDateBegin(), report.getDateEnd(), report.getDomain(), report.getAdkim(),
                report.getAspf(), report.getPolicy(), report.getSpPolicy(), report.getPct(),
                report.getCreatedAt(), records);
    }

    private Specification<DmarcReport> filter(Long organizationId,
                                              String domain, String orgName, String sourceIp,
                                              LocalDateTime dateFrom, LocalDateTime dateTo, String policy) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();

            // Unconditional tenant predicate. Everything below only narrows within
            // the caller's own data.
            predicates.add(builder.equal(root.get("organization").get("id"), organizationId));

            if (hasText(domain)) {
                predicates.add(builder.equal(builder.lower(root.get("domain")), domain.trim().toLowerCase()));
            }
            if (hasText(orgName)) {
                predicates.add(builder.like(builder.lower(root.get("orgName")),
                        "%" + orgName.trim().toLowerCase() + "%"));
            }
            if (hasText(policy)) {
                predicates.add(builder.equal(builder.lower(root.get("policy")), policy.trim().toLowerCase()));
            }
            if (dateFrom != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get("dateBegin"), dateFrom));
            }
            if (dateTo != null) {
                predicates.add(builder.lessThanOrEqualTo(root.get("dateBegin"), dateTo));
            }
            if (hasText(sourceIp) && query != null) {
                // Matching a source IP has to reach into the report's records. A join would
                // emit one row per matching record, which inflates the paging count query
                // (distinct cannot be applied there); an exists subquery keeps it one row
                // per report for both the data and the count query.
                Subquery<Integer> matching = query.subquery(Integer.class);
                Root<DmarcRecord> record = matching.from(DmarcRecord.class);
                matching.select(builder.literal(1)).where(builder.and(
                        builder.equal(record.get("report"), root),
                        builder.like(record.get("sourceIp"), sourceIp.trim() + "%")));

                predicates.add(builder.exists(matching));
            }

            return builder.and(predicates.toArray(new Predicate[0]));
        };
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
