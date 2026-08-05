package com.teknologiia.dmarc.service;

import com.teknologiia.dmarc.dto.stats.DomainStatsDTO;
import com.teknologiia.dmarc.dto.stats.OverviewStats;
import com.teknologiia.dmarc.dto.stats.SenderStats;
import com.teknologiia.dmarc.dto.stats.TimelineDataPoint;
import com.teknologiia.dmarc.model.DmarcRecord;
import com.teknologiia.dmarc.model.DmarcReport;
import com.teknologiia.dmarc.repository.DmarcReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Predicate;

/**
 * Dashboard aggregates, computed over one organization's reports.
 *
 * <p>Every figure here is derived from stored records. The timeline and top-sender
 * lists previously returned hardcoded placeholder values, which rendered as a flat
 * chart and three invented IP addresses.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StatsService {

    private final DmarcReportRepository reportRepository;

    public OverviewStats getOverview(Long organizationId, String domain,
                                     LocalDateTime dateFrom, LocalDateTime dateTo) {
        List<DmarcReport> reports = load(organizationId, domain, dateFrom, dateTo);

        long totalEmails = 0;
        long dkimPassCount = 0;
        long spfPassCount = 0;
        long dmarcPassCount = 0;
        Set<String> uniqueSources = new HashSet<>();
        Set<String> uniqueDomains = new HashSet<>();

        Map<String, Long> policyDistribution = new LinkedHashMap<>();
        policyDistribution.put("none", 0L);
        policyDistribution.put("quarantine", 0L);
        policyDistribution.put("reject", 0L);

        for (DmarcReport report : reports) {
            String policy = report.getPolicy() != null ? report.getPolicy().toLowerCase() : "none";
            policyDistribution.merge(policy, 1L, Long::sum);
            uniqueDomains.add(report.getDomain());

            for (DmarcRecord record : report.getRecords()) {
                long count = record.getCount();
                totalEmails += count;
                uniqueSources.add(record.getSourceIp());

                if (isPass(record.getDkimResult())) dkimPassCount += count;
                if (isPass(record.getSpfResult())) spfPassCount += count;
                if (isCompliant(record)) dmarcPassCount += count;
            }
        }

        List<Map<String, Object>> topSenders = getTopSenders(organizationId, 5, domain, dateFrom, dateTo)
                .stream()
                .map(s -> Map.<String, Object>of(
                        "source_ip", s.source_ip(),
                        "total_emails", s.total_emails(),
                        "spf_pass_rate", s.spf_pass_rate(),
                        "dkim_pass_rate", s.dkim_pass_rate()))
                .toList();

        List<Map<String, Object>> recentReports = reports.stream()
                .sorted(Comparator.comparing(DmarcReport::getDateBegin,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(5)
                .map(r -> Map.<String, Object>of(
                        "id", r.getId(),
                        "domain", r.getDomain(),
                        "org_name", r.getOrgName() == null ? "" : r.getOrgName(),
                        "date_begin", String.valueOf(r.getDateBegin())))
                .toList();

        return new OverviewStats(
                (long) reports.size(),
                totalEmails,
                rate(spfPassCount, totalEmails),
                rate(dkimPassCount, totalEmails),
                rate(dmarcPassCount, totalEmails),
                (long) uniqueSources.size(),
                (long) uniqueDomains.size(),
                policyDistribution,
                topSenders,
                recentReports);
    }

    /** Daily message volume and authentication outcomes over the trailing window. */
    public List<TimelineDataPoint> getTimeline(Long organizationId, int days, String domain) {
        LocalDate today = LocalDate.now();
        LocalDate start = today.minusDays(Math.max(0, days));

        // Seed every day in range so gaps render as zero rather than disappearing.
        Map<LocalDate, long[]> byDay = new TreeMap<>();
        for (LocalDate d = start; !d.isAfter(today); d = d.plusDays(1)) {
            byDay.put(d, new long[7]);
        }

        for (DmarcReport report : load(organizationId, domain, null, null)) {
            if (report.getDateBegin() == null) continue;
            LocalDate day = report.getDateBegin().toLocalDate();
            long[] bucket = byDay.get(day);
            if (bucket == null) continue;

            for (DmarcRecord record : report.getRecords()) {
                long count = record.getCount();
                bucket[0] += count;
                if (isPass(record.getSpfResult())) bucket[1] += count; else bucket[2] += count;
                if (isPass(record.getDkimResult())) bucket[3] += count; else bucket[4] += count;
                if (isCompliant(record)) bucket[5] += count; else bucket[6] += count;
            }
        }

        return byDay.entrySet().stream()
                .map(e -> {
                    long[] b = e.getValue();
                    return new TimelineDataPoint(e.getKey(), b[0], b[1], b[2], b[3], b[4], b[5], b[6]);
                })
                .toList();
    }

    /** Sending sources ranked by message volume. */
    public List<SenderStats> getTopSenders(Long organizationId, int limit, String domain,
                                           LocalDateTime dateFrom, LocalDateTime dateTo) {
        Map<String, long[]> bySource = new HashMap<>();

        for (DmarcReport report : load(organizationId, domain, dateFrom, dateTo)) {
            for (DmarcRecord record : report.getRecords()) {
                long[] totals = bySource.computeIfAbsent(record.getSourceIp(), ip -> new long[5]);
                long count = record.getCount();

                totals[0] += count;
                if (isPass(record.getSpfResult())) totals[1] += count; else totals[2] += count;
                if (isPass(record.getDkimResult())) totals[3] += count; else totals[4] += count;
            }
        }

        return bySource.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, long[]> e) -> e.getValue()[0]).reversed())
                .limit(Math.max(1, limit))
                .map(e -> {
                    long[] t = e.getValue();
                    return new SenderStats(e.getKey(), t[0], t[1], t[2], t[3], t[4],
                            rate(t[1], t[0]), rate(t[3], t[0]), t[0]);
                })
                .toList();
    }

    /** Per-domain rollup across everything the organization has ingested. */
    public List<DomainStatsDTO> getDomainStats(Long organizationId,
                                               LocalDateTime dateFrom, LocalDateTime dateTo) {
        Map<String, long[]> byDomain = new HashMap<>();

        for (DmarcReport report : load(organizationId, null, dateFrom, dateTo)) {
            long[] totals = byDomain.computeIfAbsent(report.getDomain(), d -> new long[4]);
            totals[3] += 1;

            for (DmarcRecord record : report.getRecords()) {
                long count = record.getCount();
                totals[0] += count;
                if (isPass(record.getSpfResult())) totals[1] += count;
                if (isPass(record.getDkimResult())) totals[2] += count;
            }
        }

        return byDomain.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, long[]> e) -> e.getValue()[0]).reversed())
                .map(e -> {
                    long[] t = e.getValue();
                    return new DomainStatsDTO(e.getKey(), t[0], rate(t[1], t[0]), rate(t[2], t[0]), t[3]);
                })
                .toList();
    }

    // ─── Helpers ────────────────────────────────────────────────────

    /**
     * Loads the organization's reports, optionally narrowed. Scoped at the query,
     * never filtered afterwards — an unscoped read would pull other tenants' rows
     * into memory even if they were discarded later.
     */
    private List<DmarcReport> load(Long organizationId, String domain,
                                   LocalDateTime dateFrom, LocalDateTime dateTo) {
        Predicate<DmarcReport> matches = report -> {
            if (domain != null && !domain.isBlank()
                    && !domain.equalsIgnoreCase(report.getDomain())) {
                return false;
            }
            LocalDateTime begin = report.getDateBegin();
            if (dateFrom != null && (begin == null || begin.isBefore(dateFrom))) return false;
            return !(dateTo != null && (begin == null || begin.isAfter(dateTo)));
        };

        return reportRepository.findAllForOrganisation(organizationId).stream()
                .filter(matches)
                .toList();
    }

    /** DMARC alignment is recorded in policy_evaluated: pass on either mechanism. */
    private static boolean isCompliant(DmarcRecord record) {
        return isPass(record.getDkimResult()) || isPass(record.getSpfResult());
    }

    private static boolean isPass(String result) {
        return "pass".equalsIgnoreCase(result);
    }

    private static double rate(long part, long total) {
        return total == 0 ? 0.0 : Math.round((double) part / total * 1000) / 10.0;
    }
}
