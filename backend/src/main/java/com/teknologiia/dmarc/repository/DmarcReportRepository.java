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

public interface DmarcReportRepository extends JpaRepository<DmarcReport, Long>, JpaSpecificationExecutor<DmarcReport> {
    boolean existsByReportId(String reportId);
    Optional<DmarcReport> findByReportId(String reportId);

    @Query("SELECT COUNT(r) FROM DmarcReport r WHERE (:domain IS NULL OR r.domain = :domain) AND (:dateFrom IS NULL OR r.dateBegin >= :dateFrom) AND (:dateTo IS NULL OR r.dateBegin <= :dateTo)")
    long countFiltered(@Param("domain") String domain, @Param("dateFrom") LocalDateTime dateFrom, @Param("dateTo") LocalDateTime dateTo);

    @Query("SELECT DISTINCT r.domain FROM DmarcReport r")
    List<String> findAllDomains();

    Page<DmarcReport> findAll(Specification<DmarcReport> spec, Pageable pageable);
}
