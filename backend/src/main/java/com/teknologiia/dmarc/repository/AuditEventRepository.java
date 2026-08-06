package com.teknologiia.dmarc.repository;

import com.teknologiia.dmarc.model.AuditEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    /**
     * The trail, newest first, narrowed by whatever the caller supplied.
     *
     * <p>Every filter is optional and a null one drops out of the predicate, so one
     * query serves the unfiltered view and every combination of the three. Written
     * out rather than derived because Spring Data's method names cannot express
     * "ignore this parameter when it is null".
     */
    @Query("""
            SELECT e FROM AuditEvent e
            WHERE (:actor IS NULL OR LOWER(e.actor) = LOWER(:actor))
              AND (:action IS NULL OR e.action = :action)
              AND (:since IS NULL OR e.at >= :since)
            ORDER BY e.at DESC, e.id DESC
            """)
    Page<AuditEvent> search(@Param("actor") String actor,
                            @Param("action") String action,
                            @Param("since") LocalDateTime since,
                            Pageable pageable);

    /** The distinct actions present, so the filter offers only what exists. */
    @Query("SELECT DISTINCT e.action FROM AuditEvent e ORDER BY e.action")
    List<String> distinctActions();
}
