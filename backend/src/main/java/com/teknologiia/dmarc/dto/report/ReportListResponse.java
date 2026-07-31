package com.teknologiia.dmarc.dto.report;

import java.time.LocalDateTime;

public record ReportListResponse(
    Long id,
    String report_id,
    String org_name,
    LocalDateTime date_begin,
    LocalDateTime date_end,
    String domain,
    String policy,
    Integer record_count,
    Integer total_emails,
    LocalDateTime created_at
) {}
