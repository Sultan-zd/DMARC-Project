package com.teknologiia.dmarc.dto.stats;

public record SenderStats(
    String source_ip,
    Long total_emails,
    Long spf_pass,
    Long spf_fail,
    Long dkim_pass,
    Long dkim_fail,
    Double spf_pass_rate,
    Double dkim_pass_rate,
    Long record_count
) {}
