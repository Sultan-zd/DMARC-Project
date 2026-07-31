package com.teknologiia.dmarc.dto.analysis;

import java.time.LocalDateTime;
import java.util.List;

public record DomainAnalysisResponse(
    Long id,
    String domain,
    SecurityScore score,
    List<DnsRecordResult> records,
    List<RecommendationDTO> recommendations,
    LocalDateTime analyzed_at,
    String analyzed_by
) {}
