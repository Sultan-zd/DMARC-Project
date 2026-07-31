package com.teknologiia.dmarc.dto.report;

import java.util.List;

public record PaginatedResponse<T>(
    List<T> items,
    long total,
    int page,
    int page_size,
    int total_pages
) {}
