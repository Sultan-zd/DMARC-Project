package com.teknologiia.dmarc.repository;

import com.teknologiia.dmarc.model.DmarcRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface DmarcRecordRepository extends JpaRepository<DmarcRecord, Long> {
    List<DmarcRecord> findByReportId(Long reportId);

    @Query("SELECT SUM(r.count) FROM DmarcRecord r JOIN r.report rep WHERE (:domain IS NULL OR rep.domain = :domain) AND (:dateFrom IS NULL OR rep.dateBegin >= :dateFrom) AND (:dateTo IS NULL OR rep.dateBegin <= :dateTo)")
    Long sumTotalEmails(@Param("domain") String domain, @Param("dateFrom") LocalDateTime dateFrom, @Param("dateTo") LocalDateTime dateTo);
}
