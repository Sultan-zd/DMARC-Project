package com.teknologiia.dmarc.dto.alert;

import java.time.LocalDateTime;

public record AlertResponse(
    Long id,
    String alert_type,
    String severity,
    String message,
    String details,
    String domain,
    Boolean is_read,
    LocalDateTime created_at
) {}
