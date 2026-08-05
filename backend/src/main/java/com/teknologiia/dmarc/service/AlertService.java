package com.teknologiia.dmarc.service;

import com.teknologiia.dmarc.dto.alert.AlertCount;
import com.teknologiia.dmarc.dto.alert.AlertResponse;
import com.teknologiia.dmarc.dto.report.PaginatedResponse;
import com.teknologiia.dmarc.model.Alert;
import com.teknologiia.dmarc.repository.AlertRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Alerts, scoped to the caller's organization.
 *
 * <p>Every method takes the organization id and applies it as a predicate. Nothing
 * here may fall back to an unscoped repository call.
 */
@Service
@RequiredArgsConstructor
public class AlertService {

    private final AlertRepository alertRepository;

    public PaginatedResponse<AlertResponse> getAlerts(Long organizationId, String severity,
                                                      Boolean isRead, String domain,
                                                      int page, int size) {
        Specification<Alert> spec = buildSpecification(organizationId, severity, isRead, domain);
        Page<Alert> dbPage = alertRepository.findAll(spec,
                PageRequest.of(Math.max(0, page - 1), size, Sort.by(Sort.Direction.DESC, "createdAt")));

        List<AlertResponse> items = dbPage.getContent().stream()
                .map(this::toResponse)
                .toList();

        return new PaginatedResponse<>(items, dbPage.getTotalElements(),
                page, size, dbPage.getTotalPages());
    }

    public AlertCount getAlertCount(Long organizationId) {
        // Counted through the tenant-scoped specification rather than repository
        // count(), which spans every organization.
        long total = alertRepository.count(buildSpecification(organizationId, null, null, null));
        long unread = alertRepository.countByOrganizationIdAndReadFalse(organizationId);
        long critical = alertRepository.countByOrganizationIdAndReadFalseAndSeverity(organizationId, "critical");
        long high = alertRepository.countByOrganizationIdAndReadFalseAndSeverity(organizationId, "high");
        return new AlertCount(total, unread, critical, high);
    }

    /**
     * Marks one alert read. The lookup is scoped, so an id belonging to another
     * organization simply matches nothing instead of being modified.
     */
    @Transactional
    public void markAsRead(Long organizationId, Long id) {
        alertRepository.findByIdAndOrganizationId(id, organizationId).ifPresent(alert -> {
            alert.setRead(true);
            alertRepository.save(alert);
        });
    }

    @Transactional
    public void markAllAsRead(Long organizationId) {
        alertRepository.markAllAsRead(organizationId);
    }

    // ─── HELPERS ────────────────────────────────────────────────────

    private Specification<Alert> buildSpecification(Long organizationId, String severity,
                                                    Boolean isRead, String domain) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // The tenant predicate is unconditional: it is what makes every other
            // filter safe to apply.
            predicates.add(cb.equal(root.get("organization").get("id"), organizationId));

            if (severity != null && !severity.isBlank() && !"all".equalsIgnoreCase(severity)) {
                predicates.add(cb.equal(root.get("severity"), severity));
            }
            if (isRead != null) {
                predicates.add(cb.equal(root.get("read"), isRead));
            }
            if (domain != null && !domain.isBlank()) {
                predicates.add(cb.equal(root.get("domain"), domain));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private AlertResponse toResponse(Alert alert) {
        return new AlertResponse(
                alert.getId(),
                alert.getAlertType(),
                alert.getSeverity(),
                alert.getMessage(),
                alert.getDetails(),
                alert.getDomain(),
                alert.isRead(),
                alert.getCreatedAt()
        );
    }
}
