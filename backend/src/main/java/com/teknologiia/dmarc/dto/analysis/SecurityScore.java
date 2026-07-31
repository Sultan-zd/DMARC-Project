package com.teknologiia.dmarc.dto.analysis;

import java.util.Map;

public record SecurityScore(
    Integer score,
    String grade,
    String color,
    Map<String, Integer> breakdown
) {}
