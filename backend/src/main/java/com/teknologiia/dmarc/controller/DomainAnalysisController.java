package com.teknologiia.dmarc.controller;

import com.teknologiia.dmarc.dto.analysis.DomainAnalysisRequest;
import com.teknologiia.dmarc.dto.analysis.TransportSecurityResult;
import com.teknologiia.dmarc.dto.analysis.DomainAnalysisResponse;
import com.teknologiia.dmarc.dto.analysis.ScoringModelResponse;
import com.teknologiia.dmarc.dto.report.PaginatedResponse;
import com.teknologiia.dmarc.service.DomainAnalysisService;
import com.teknologiia.dmarc.service.TransportSecurityService;
import com.teknologiia.dmarc.service.ScoringModel;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import com.teknologiia.dmarc.security.AuthenticatedUser;
import com.teknologiia.dmarc.repository.OrganizationRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/analysis")
@RequiredArgsConstructor
public class DomainAnalysisController {

    private final DomainAnalysisService domainAnalysisService;
    private final OrganizationRepository organizationRepository;
    private final TransportSecurityService transportSecurityService;

    @PostMapping("/domain")
    public DomainAnalysisResponse analyzeDomain(
            @Valid @RequestBody DomainAnalysisRequest request,
            @AuthenticationPrincipal AuthenticatedUser caller) {
        // A signed-in scan is owned by the caller's organization, so its findings
        // reach that team's alert feed.
        return domainAnalysisService.analyzeDomain(
                request.domain(), caller.getUsername(),
                organizationRepository.getReferenceById(caller.getOrganizationId()),
                true, request.dkimSelector());
    }

    /**
     * Whether mail to this domain travels encrypted.
     *
     * <p>Separate from the analysis above, and called separately by the page,
     * because it is slower by a different order: the declared half is DNS, but
     * reaching each MX server on port 25 and asking it four questions is seconds
     * rather than milliseconds. Folding it in would make every analysis wait for it.
     */
    @PostMapping("/transport")
    public TransportSecurityResult transportSecurity(
            @Valid @RequestBody DomainAnalysisRequest request,
            @AuthenticationPrincipal AuthenticatedUser caller) {
        return transportSecurityService.check(request.domain());
    }

    @GetMapping("/history")
    public PaginatedResponse<DomainAnalysisResponse> getHistory(
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(name = "page_size", required = false, defaultValue = "20") int pageSize,
            @AuthenticationPrincipal AuthenticatedUser caller) {
        return domainAnalysisService.getHistory(caller.getOrganizationId(), page, pageSize);
    }

    /**
     * The rules the score is built from.
     *
     * <p>Served from the same constants the scorer reads, so the explanation on
     * screen cannot drift away from what actually ran.
     */
    @GetMapping("/scoring-model")
    public ScoringModelResponse scoringModel() {
        return ScoringModel.published();
    }

    @GetMapping("/{id}")
    public DomainAnalysisResponse getAnalysis(@PathVariable Long id,
                                             @AuthenticationPrincipal AuthenticatedUser caller) {
        return domainAnalysisService.getAnalysis(caller.getOrganizationId(), id);
    }
}
