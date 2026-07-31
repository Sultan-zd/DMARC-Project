package com.teknologiia.dmarc.dto.report;

public record RecordResponse(
    Long id,
    String source_ip,
    Integer count,
    String disposition,
    String dkim_result,
    String spf_result,
    String dkim_domain,
    String spf_domain,
    String header_from,
    String envelope_from,
    String dkim_selector
) {}
