package com.teknologiia.dmarc.dto.analysis;

public record RecommendationDTO(
    String severity,
    String category,
    String message,
    String action
) {}
