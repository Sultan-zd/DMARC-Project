package com.teknologiia.dmarc.controller;

import com.teknologiia.dmarc.dto.stats.DomainPosture;
import com.teknologiia.dmarc.dto.stats.DomainStatsDTO;
import com.teknologiia.dmarc.dto.stats.OverviewStats;
import com.teknologiia.dmarc.dto.stats.SenderStats;
import com.teknologiia.dmarc.dto.stats.TimelineDataPoint;
import com.teknologiia.dmarc.service.DomainAnalysisService;
import com.teknologiia.dmarc.service.StatsService;
import com.teknologiia.dmarc.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/stats")
@RequiredArgsConstructor
public class StatsController {

    private final StatsService statsService;
    private final DomainAnalysisService domainAnalysisService;

    @GetMapping("/overview")
    public OverviewStats getOverview(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @RequestParam(required = false) String domain,
            @RequestParam(name = "date_from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateFrom,
            @RequestParam(name = "date_to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateTo) {
        return statsService.getOverview(caller.getOrganizationId(), domain, dateFrom, dateTo);
    }

    @GetMapping("/timeline")
    public List<TimelineDataPoint> getTimeline(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @RequestParam(required = false, defaultValue = "30") int days,
            @RequestParam(required = false) String domain) {
        return statsService.getTimeline(caller.getOrganizationId(), days, domain);
    }

    @GetMapping("/top-senders")
    public List<SenderStats> getTopSenders(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @RequestParam(required = false, defaultValue = "20") int limit,
            @RequestParam(required = false) String domain,
            @RequestParam(name = "date_from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateFrom,
            @RequestParam(name = "date_to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateTo) {
        return statsService.getTopSenders(caller.getOrganizationId(), limit, domain, dateFrom, dateTo);
    }

    @GetMapping("/domains")
    public List<DomainStatsDTO> getDomains(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @RequestParam(name = "date_from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateFrom,
            @RequestParam(name = "date_to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateTo) {
        return statsService.getDomainStats(caller.getOrganizationId(), dateFrom, dateTo);
    }

    /**
     * Configuration posture per domain, weakest first.
     *
     * <p>Separate from the other figures here on purpose: these describe how domains
     * are set up, while the rest describe mail that was actually sent. Mixing them
     * into one number would hide which of the two a problem lies in.
     */
    @GetMapping("/domain-posture")
    public List<DomainPosture> getDomainPosture(@AuthenticationPrincipal AuthenticatedUser caller) {
        return domainAnalysisService.getDomainPosture(caller.getOrganizationId());
    }
}
