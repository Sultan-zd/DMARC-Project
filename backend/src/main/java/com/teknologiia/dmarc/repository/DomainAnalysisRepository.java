package com.teknologiia.dmarc.repository;

import com.teknologiia.dmarc.model.DomainAnalysis;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DomainAnalysisRepository extends JpaRepository<DomainAnalysis, Long> {
    List<DomainAnalysis> findByDomainOrderByAnalyzedAtDesc(String domain);
    Page<DomainAnalysis> findAllByOrderByAnalyzedAtDesc(Pageable pageable);
}
