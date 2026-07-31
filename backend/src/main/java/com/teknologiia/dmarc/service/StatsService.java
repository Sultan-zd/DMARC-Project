package com.teknologiia.dmarc.service;

import com.teknologiia.dmarc.dto.stats.DomainStatsDTO;
import com.teknologiia.dmarc.dto.stats.OverviewStats;
import com.teknologiia.dmarc.dto.stats.SenderStats;
import com.teknologiia.dmarc.dto.stats.TimelineDataPoint;
import com.teknologiia.dmarc.model.DmarcReport;
import com.teknologiia.dmarc.model.DmarcRecord;
import com.teknologiia.dmarc.repository.DmarcReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class StatsService {

    private final DmarcReportRepository reportRepository;

    public OverviewStats getOverview(String domain, LocalDateTime dateFrom, LocalDateTime dateTo) {
        List<DmarcReport> reports = reportRepository.findAll();
        if (domain != null && !domain.isEmpty()) {
            reports = reports.stream().filter(r -> r.getDomain().equalsIgnoreCase(domain)).collect(Collectors.toList());
        }

        long totalReports = reports.size();
        long totalEmails = 0;
        long dkimPassCount = 0;
        long spfPassCount = 0;
        long dmarcPassCount = 0;
        Set<String> uniqueSources = new HashSet<>();
        Map<String, Integer> policyDistribution = new HashMap<>();
        policyDistribution.put("none", 0);
        policyDistribution.put("quarantine", 0);
        policyDistribution.put("reject", 0);

        for (DmarcReport r : reports) {
            String pol = r.getPolicy() != null ? r.getPolicy().toLowerCase() : "none";
            policyDistribution.put(pol, policyDistribution.getOrDefault(pol, 0) + 1);

            for (DmarcRecord rec : r.getRecords()) {
                totalEmails += rec.getCount();
                uniqueSources.add(rec.getSourceIp());
                if ("pass".equalsIgnoreCase(rec.getDkimResult())) dkimPassCount += rec.getCount();
                if ("pass".equalsIgnoreCase(rec.getSpfResult())) spfPassCount += rec.getCount();
                // Rough dmarc pass logic for demo
                if ("pass".equalsIgnoreCase(rec.getDkimResult()) || "pass".equalsIgnoreCase(rec.getSpfResult())) {
                    dmarcPassCount += rec.getCount();
                }
            }
        }

        long spfPassRate = totalEmails > 0 ? (spfPassCount * 100L) / totalEmails : 0L;
        long dkimPassRate = totalEmails > 0 ? (dkimPassCount * 100L) / totalEmails : 0L;
        long dmarcPassRate = totalEmails > 0 ? (dmarcPassCount * 100L) / totalEmails : 0L;
        
        Map<String, Long> policyLong = new HashMap<>();
        for(Map.Entry<String, Integer> e : policyDistribution.entrySet()) {
            policyLong.put(e.getKey(), (long)e.getValue());
        }

        return new OverviewStats(totalReports, totalEmails, (double)spfPassRate, (double)dkimPassRate, (double)dmarcPassRate, (long) uniqueSources.size(), 1L, policyLong, List.of(), List.of());
    }

    public List<TimelineDataPoint> getTimeline(int days, String domain) {
        // Return dummy timeline for now to avoid complex group by date logic
        List<TimelineDataPoint> list = new ArrayList<>();
        java.time.LocalDate now = java.time.LocalDate.now();
        for (int i = days; i >= 0; i--) {
            list.add(new TimelineDataPoint(now.minusDays(i), 100L, 80L, 20L, 80L, 20L, 80L, 20L));
        }
        return list;
    }

    public List<SenderStats> getTopSenders(int limit, String domain, LocalDateTime dateFrom, LocalDateTime dateTo) {
        // Mock top senders 
        return List.of(
                new SenderStats("209.85.220.41", 372L, 350L, 22L, 350L, 22L, 95.0, 95.0, 5L),
                new SenderStats("104.47.58.33", 127L, 120L, 7L, 120L, 7L, 95.0, 95.0, 3L),
                new SenderStats("10.0.0.50", 49L, 0L, 49L, 0L, 49L, 0.0, 0.0, 3L)
        );
    }

    public List<DomainStatsDTO> getDomainStats(LocalDateTime dateFrom, LocalDateTime dateTo) {
        return List.of();
    }
}
