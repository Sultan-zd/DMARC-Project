package com.teknologiia.dmarc.controller;

import com.teknologiia.dmarc.dto.report.PaginatedResponse;
import com.teknologiia.dmarc.dto.report.ReportDetailResponse;
import com.teknologiia.dmarc.dto.report.ReportListResponse;
import com.teknologiia.dmarc.service.ReportService;
import com.teknologiia.dmarc.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @GetMapping
    public PaginatedResponse<ReportListResponse> getReports(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(name = "page_size", required = false, defaultValue = "20") int pageSize,
            @RequestParam(required = false) String domain,
            @RequestParam(name = "org_name", required = false) String orgName,
            @RequestParam(name = "source_ip", required = false) String sourceIp,
            @RequestParam(name = "date_from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateFrom,
            @RequestParam(name = "date_to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateTo,
            @RequestParam(required = false) String policy,
            @RequestParam(name = "sort_by", required = false, defaultValue = "date_begin") String sortBy,
            @RequestParam(name = "sort_order", required = false, defaultValue = "desc") String sortOrder) {
        return reportService.getReports(caller.getOrganizationId(), domain, orgName, sourceIp,
                dateFrom, dateTo, policy, sortBy, sortOrder, page, pageSize);
    }

    @GetMapping("/{id}")
    public ReportDetailResponse getReport(@AuthenticationPrincipal AuthenticatedUser caller,
                                         @PathVariable Long id) {
        return reportService.getReport(caller.getOrganizationId(), id);
    }
}
