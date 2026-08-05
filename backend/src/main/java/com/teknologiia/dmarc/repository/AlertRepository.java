package com.teknologiia.dmarc.repository;

import com.teknologiia.dmarc.model.Alert;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Alerts are tenant-scoped: every method here takes the organization explicitly.
 * Inherited methods such as {@code findAll()} and {@code findById()} are not, so
 * callers must not use them for anything a user sees.
 */
public interface AlertRepository extends JpaRepository<Alert, Long>, JpaSpecificationExecutor<Alert> {

    long countByOrganizationIdAndReadFalse(Long organizationId);

    long countByOrganizationIdAndReadFalseAndSeverity(Long organizationId, String severity);

    Page<Alert> findAll(Specification<Alert> spec, Pageable pageable);

    /** Scoped lookup for single-alert operations, so one tenant cannot touch another's row. */
    Optional<Alert> findByIdAndOrganizationId(Long id, Long organizationId);

    /**
     * Bulk read-marking, bounded to one organization. Without the tenant predicate
     * this statement would mark every organization's alerts as read.
     */
    @Modifying @Transactional
    @Query("UPDATE Alert a SET a.read = true WHERE a.read = false AND a.organization.id = :organizationId")
    void markAllAsRead(@Param("organizationId") Long organizationId);
}
