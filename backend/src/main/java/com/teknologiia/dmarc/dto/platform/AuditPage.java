package com.teknologiia.dmarc.dto.platform;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A page of the audit trail, with what the filter can offer.
 *
 * @param actions every action present in the table, so the filter lists what
 *                actually happened rather than every constant the code knows about
 */
public record AuditPage(
        List<Entry> entries,
        List<String> actions,
        long total,
        int page,
        int pageSize
) {
    /**
     * @param targetLabel the target's name as it was at the time, copied rather than
     *                    joined — the row this describes is often the one that was
     *                    deleted
     */
    public record Entry(
            Long id,
            LocalDateTime at,
            String actor,
            String action,
            String targetType,
            Long targetId,
            String targetLabel,
            String detail,
            String clientIp
    ) {}
}
