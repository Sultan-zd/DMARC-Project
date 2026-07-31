package com.teknologiia.dmarc.repository;

import com.teknologiia.dmarc.model.Alert;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface AlertRepository extends JpaRepository<Alert, Long>, JpaSpecificationExecutor<Alert> {
    long countByReadFalse();
    long countByReadFalseAndSeverity(String severity);
    Page<Alert> findAll(Specification<Alert> spec, Pageable pageable);

    @Modifying @Transactional
    @Query("UPDATE Alert a SET a.read = true WHERE a.read = false")
    void markAllAsRead();
}
