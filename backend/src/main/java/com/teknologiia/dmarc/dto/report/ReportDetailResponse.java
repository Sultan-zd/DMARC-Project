package com.teknologiia.dmarc.dto.report;

import java.time.LocalDateTime;
import java.util.List;

public record ReportDetailResponse(
    Long id,
    String report_id,
    String org_name,
    String org_email,
    LocalDateTime date_begin,
    LocalDateTime date_end,
    String domain,
    String adkim,
    String aspf,
    String policy,
    String sp_policy,
    Integer pct,
    LocalDateTime created_at,
    List<RecordResponse> records
) {}
