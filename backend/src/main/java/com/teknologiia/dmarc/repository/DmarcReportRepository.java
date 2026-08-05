package com.teknologiia.dmarc.repository;

import com.teknologiia.dmarc.model.DmarcReport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Reports are tenant-scoped.
 *
 * <p>Every method takes an {@code organizationId}. The inherited {@code findAll()},
 * {@code findById()} and {@code count()} are deliberately not used for
 * user-facing reads — they span all tenants.
 */
public interface DmarcReportRepository extends JpaRepository<DmarcReport, Long>, JpaSpecificationExecutor<DmarcReport> {

    /**
     * Deduplication check, scoped to one organization: two organizations can
     * legitimately hold the same provider report, and one importing it must not
     * make it look like a duplicate to the other.
     */
    boolean existsByOrganizationIdAndReportId(Long organizationId, String reportId);

    Optional<DmarcReport> findByIdAndOrganizationId(Long id, Long organizationId);

    @Query("""
            SELECT COUNT(r) FROM DmarcReport r
            WHERE r.organization.id = :organizationId
              AND (:domain IS NULL OR r.domain = :domain)
              AND (:dateFrom IS NULL OR r.dateBegin >= :dateFrom)
              AND (:dateTo IS NULL OR r.dateBegin <= :dateTo)
            """)
    long countFiltered(@Param("organizationId") Long organizationId,
                       @Param("domain") String domain,
                       @Param("dateFrom") LocalDateTime dateFrom,
                       @Param("dateTo") LocalDateTime dateTo);

    @Query("SELECT DISTINCT r.domain FROM DmarcReport r WHERE r.organization.id = :organizationId")
    List<String> findAllDomains(@Param("organizationId") Long organizationId);

    long countByOrganizationId(Long organizationId);

    /**
     * The window the stored reports cover, as {@code [earliest, latest]}.
     *
     * <p>Aggregated in the database rather than by loading every report, which for a
     * long-running mailbox is a large result set to build two dates from.
     */
    @Query("""
            SELECT MIN(r.dateBegin), MAX(r.dateEnd) FROM DmarcReport r
            WHERE r.organization.id = :organizationId
            """)
    List<Object[]> findReportingWindow(@Param("organizationId") Long organizationId);

    /** Backs the statistics screens, which previously loaded every tenant's reports. */
    @Query("SELECT r FROM DmarcReport r LEFT JOIN FETCH r.records WHERE r.organization.id = :organizationId")
    List<DmarcReport> findAllForOrganisation(@Param("organizationId") Long organizationId);

    /**
     * Loads reports matching the export filters together with their records in one
     * pass, so building an export does not issue a query per report.
     */
    @Query("""
            SELECT DISTINCT r FROM DmarcReport r
            LEFT JOIN FETCH r.records
            WHERE r.organization.id = :organizationId
              AND (:domain IS NULL OR r.domain = :domain)
              AND (:dateFrom IS NULL OR r.dateBegin >= :dateFrom)
              AND (:dateTo IS NULL OR r.dateBegin <= :dateTo)
            ORDER BY r.dateBegin DESC
            """)
    List<DmarcReport> findForExport(@Param("organizationId") Long organizationId,
                                    @Param("domain") String domain,
                                    @Param("dateFrom") LocalDateTime dateFrom,
                                    @Param("dateTo") LocalDateTime dateTo);

    Page<DmarcReport> findAll(Specification<DmarcReport> spec, Pageable pageable);
}
