package com.teknologiia.dmarc.controller;

import com.teknologiia.dmarc.dto.stats.DomainStatsDTO;
import com.teknologiia.dmarc.dto.stats.OverviewStats;
import com.teknologiia.dmarc.dto.stats.SenderStats;
import com.teknologiia.dmarc.dto.stats.TimelineDataPoint;
import com.teknologiia.dmarc.service.StatsService;
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

    @GetMapping("/overview")
    public OverviewStats getOverview(
            @RequestParam(required = false) String domain,
            @RequestParam(name = "date_from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateFrom,
            @RequestParam(name = "date_to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateTo) {
        return statsService.getOverview(domain, dateFrom, dateTo);
    }

    @GetMapping("/timeline")
    public List<TimelineDataPoint> getTimeline(
            @RequestParam(required = false, defaultValue = "30") int days,
            @RequestParam(required = false) String domain) {
        return statsService.getTimeline(days, domain);
    }

    @GetMapping("/top-senders")
    public List<SenderStats> getTopSenders(
            @RequestParam(required = false, defaultValue = "20") int limit,
            @RequestParam(required = false) String domain,
            @RequestParam(name = "date_from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateFrom,
            @RequestParam(name = "date_to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateTo) {
        return statsService.getTopSenders(limit, domain, dateFrom, dateTo);
    }

    @GetMapping("/domains")
    public List<DomainStatsDTO> getDomains(
            @RequestParam(name = "date_from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateFrom,
            @RequestParam(name = "date_to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateTo) {
        return statsService.getDomainStats(dateFrom, dateTo);
    }
}
