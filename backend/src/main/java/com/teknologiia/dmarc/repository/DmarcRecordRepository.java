package com.teknologiia.dmarc.repository;

import com.teknologiia.dmarc.model.DmarcRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface DmarcRecordRepository extends JpaRepository<DmarcRecord, Long> {

    List<DmarcRecord> findByReportId(Long reportId);

    /** Message volume across an organization's reports. Tenant-scoped like every read. */
    @Query("""
            SELECT SUM(r.count) FROM DmarcRecord r
            JOIN r.report rep
            WHERE rep.organization.id = :organizationId
              AND (:domain IS NULL OR rep.domain = :domain)
              AND (:dateFrom IS NULL OR rep.dateBegin >= :dateFrom)
              AND (:dateTo IS NULL OR rep.dateBegin <= :dateTo)
            """)
    Long sumTotalEmails(@Param("organizationId") Long organizationId,
                        @Param("domain") String domain,
                        @Param("dateFrom") LocalDateTime dateFrom,
                        @Param("dateTo") LocalDateTime dateTo);

    /**
     * DKIM selectors this domain has actually been observed signing with.
     *
     * <p>Selectors cannot be enumerated from DNS — you can only guess names and see
     * which resolve. But an organization's own aggregate reports name the selectors
     * that were really used, which turns guessing into looking up.
     */
    @Query("""
            SELECT DISTINCT r.dkimSelector FROM DmarcRecord r
            JOIN r.report rep
            WHERE rep.organization.id = :organizationId
              AND rep.domain = :domain
              AND r.dkimSelector IS NOT NULL
              AND r.dkimSelector <> ''
            """)
    List<String> findKnownDkimSelectors(@Param("organizationId") Long organizationId,
                                        @Param("domain") String domain);
}
