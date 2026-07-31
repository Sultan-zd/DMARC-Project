package com.teknologiia.dmarc.controller;

import com.teknologiia.dmarc.dto.analysis.DomainAnalysisRequest;
import com.teknologiia.dmarc.dto.analysis.DomainAnalysisResponse;
import com.teknologiia.dmarc.dto.report.PaginatedResponse;
import com.teknologiia.dmarc.service.DomainAnalysisService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/analysis")
@RequiredArgsConstructor
public class DomainAnalysisController {

    private final DomainAnalysisService domainAnalysisService;

    @PostMapping("/domain")
    public DomainAnalysisResponse analyzeDomain(
            @Valid @RequestBody DomainAnalysisRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        return domainAnalysisService.analyzeDomain(request.domain(), userDetails.getUsername());
    }

    @GetMapping("/history")
    public PaginatedResponse<DomainAnalysisResponse> getHistory(
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(name = "page_size", required = false, defaultValue = "20") int pageSize) {
        return domainAnalysisService.getHistory(page, pageSize);
    }

    @GetMapping("/{id}")
    public DomainAnalysisResponse getAnalysis(@PathVariable Long id) {
        return domainAnalysisService.getAnalysis(id);
    }
}
