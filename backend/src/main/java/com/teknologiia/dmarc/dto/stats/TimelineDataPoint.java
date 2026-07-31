package com.teknologiia.dmarc.dto.stats;

import java.time.LocalDate;

public record TimelineDataPoint(
    LocalDate date,
    Long total,
    Long spf_pass,
    Long spf_fail,
    Long dkim_pass,
    Long dkim_fail,
    Long dmarc_pass,
    Long dmarc_fail
) {}
