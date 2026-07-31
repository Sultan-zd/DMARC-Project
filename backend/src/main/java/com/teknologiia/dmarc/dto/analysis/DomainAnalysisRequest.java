package com.teknologiia.dmarc.dto.analysis;

import jakarta.validation.constraints.NotBlank;

public record DomainAnalysisRequest(
    @NotBlank String domain
) {}
