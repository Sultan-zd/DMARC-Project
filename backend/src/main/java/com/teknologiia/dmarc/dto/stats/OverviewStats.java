package com.teknologiia.dmarc.dto.stats;

import java.util.List;
import java.util.Map;

public record OverviewStats(
    Long total_reports,
    Long total_emails,
    Double spf_pass_rate,
    Double dkim_pass_rate,
    Double dmarc_pass_rate,
    Long unique_sources,
    Long unique_domains,
    Map<String, Long> policy_distribution,
    List<Map<String, Object>> top_senders,
    List<Map<String, Object>> recent_reports
) {}
