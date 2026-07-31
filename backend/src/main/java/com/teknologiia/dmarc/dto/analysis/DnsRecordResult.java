package com.teknologiia.dmarc.dto.analysis;

import java.util.Map;

public record DnsRecordResult(
    String type,
    String status,
    String rawRecord,
    Map<String, Object> parsed,
    String summary
) {}
