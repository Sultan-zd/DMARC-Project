package com.teknologiia.dmarc.repository;

import com.teknologiia.dmarc.model.DomainAnalysis;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Analyses are tenant-scoped, with one deliberate exception: anonymous public scans
 * are stored with a null organization and are the only rows the public endpoints may
 * read. The unscoped inherited finders must not be used for user-facing reads.
 */
public interface DomainAnalysisRepository extends JpaRepository<DomainAnalysis, Long> {

    /** History for one organization. */
    Page<DomainAnalysis> findByOrganizationIdOrderByAnalyzedAtDesc(Long organizationId, Pageable pageable);

    long countByOrganizationId(Long organizationId);

    /** Scoped fetch, so an id belonging to another tenant simply matches nothing. */
    Optional<DomainAnalysis> findByIdAndOrganizationId(Long id, Long organizationId);

    /**
     * Latest anonymous analysis of a domain. Restricted to rows with no owner: the
     * public scanner must never surface an organization's private analysis, which
     * would also expose the username that ran it.
     */
    Optional<DomainAnalysis> findFirstByDomainAndOrganizationIsNullOrderByAnalyzedAtDesc(String domain);

    /**
     * The most recent analysis per domain for one organization — the current posture
     * of everything the team has looked at.
     */
    @Query("""
            SELECT a FROM DomainAnalysis a
            WHERE a.organization.id = :organizationId
              AND a.analyzedAt = (
                  SELECT MAX(b.analyzedAt) FROM DomainAnalysis b
                  WHERE b.domain = a.domain AND b.organization.id = :organizationId
              )
            ORDER BY a.score ASC
            """)
    List<DomainAnalysis> findLatestPerDomain(@Param("organizationId") Long organizationId);

    /** Previous analysis of a domain, used to show whether posture moved. */
    @Query("""
            SELECT a FROM DomainAnalysis a
            WHERE a.organization.id = :organizationId AND a.domain = :domain
            ORDER BY a.analyzedAt DESC
            """)
    List<DomainAnalysis> findHistoryForDomain(@Param("organizationId") Long organizationId,
                                              @Param("domain") String domain);
}
