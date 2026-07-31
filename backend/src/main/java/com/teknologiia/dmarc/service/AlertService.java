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

@Service
@RequiredArgsConstructor
public class AlertService {

    private final AlertRepository alertRepository;

    public PaginatedResponse<AlertResponse> getAlerts(String severity, Boolean isRead, String domain, int page, int size) {
        Specification<Alert> spec = buildSpecification(severity, isRead, domain);
        Page<Alert> dbPage = alertRepository.findAll(spec,
                PageRequest.of(Math.max(0, page - 1), size, Sort.by(Sort.Direction.DESC, "createdAt")));

        List<AlertResponse> items = dbPage.getContent().stream()
                .map(this::toResponse)
                .toList();

        return new PaginatedResponse<>(items, dbPage.getTotalElements(),
                page, size, dbPage.getTotalPages());
    }

    public AlertCount getAlertCount() {
        long total = alertRepository.count();
        long unread = alertRepository.countByReadFalse();
        long critical = alertRepository.countByReadFalseAndSeverity("critical");
        long high = alertRepository.countByReadFalseAndSeverity("high");
        return new AlertCount(total, unread, critical, high);
    }

    @Transactional
    public void markAsRead(Long id) {
        alertRepository.findById(id).ifPresent(alert -> {
            alert.setRead(true);
            alertRepository.save(alert);
        });
    }

    @Transactional
    public void markAllAsRead() {
        alertRepository.markAllAsRead();
    }

    // ─── HELPERS ────────────────────────────────────────────────────

    private Specification<Alert> buildSpecification(String severity, Boolean isRead, String domain) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

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
