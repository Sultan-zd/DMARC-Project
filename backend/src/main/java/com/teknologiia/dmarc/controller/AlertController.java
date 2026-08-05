package com.teknologiia.dmarc.controller;

import com.teknologiia.dmarc.dto.alert.AlertCount;
import com.teknologiia.dmarc.dto.alert.AlertResponse;
import com.teknologiia.dmarc.dto.report.PaginatedResponse;
import com.teknologiia.dmarc.service.AlertService;
import com.teknologiia.dmarc.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/alerts")
@RequiredArgsConstructor
public class AlertController {

    private final AlertService alertService;

    @GetMapping
    public PaginatedResponse<AlertResponse> getAlerts(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @RequestParam(required = false) String severity,
            @RequestParam(name = "is_read", required = false) Boolean isRead,
            @RequestParam(required = false) String domain,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(name = "page_size", required = false, defaultValue = "50") int pageSize) {
        return alertService.getAlerts(caller.getOrganizationId(), severity, isRead, domain, page, pageSize);
    }

    @GetMapping("/count")
    public AlertCount getAlertCount(@AuthenticationPrincipal AuthenticatedUser caller) {
        return alertService.getAlertCount(caller.getOrganizationId());
    }

    @PatchMapping("/{id}/read")
    public Map<String, Boolean> markAsRead(@AuthenticationPrincipal AuthenticatedUser caller,
                                          @PathVariable Long id) {
        alertService.markAsRead(caller.getOrganizationId(), id);
        return Map.of("success", true);
    }

    @PatchMapping("/mark-all-read")
    public Map<String, Boolean> markAllAsRead(@AuthenticationPrincipal AuthenticatedUser caller) {
        alertService.markAllAsRead(caller.getOrganizationId());
        return Map.of("success", true);
    }
}
