package com.teknologiia.dmarc.dto.stats;

public record DomainStatsDTO(
    String domain,
    Long total_emails,
    Double spf_pass_rate,
    Double dkim_pass_rate,
    Long reports_count
) {}
