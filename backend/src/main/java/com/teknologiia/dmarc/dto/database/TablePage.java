package com.teknologiia.dmarc.dto.database;

import java.util.List;
import java.util.Map;

/**
 * A page of rows from one table.
 *
 * @param primaryKey     the column a row can be deleted by, or null when the table
 *                       has none and rows cannot be addressed individually
 * @param maskedColumns  columns returned as bullets unless explicitly revealed:
 *                       password hashes, encrypted secrets, tokens. Present in the
 *                       list so the interface can say what it is hiding
 */
public record TablePage(
        String table,
        List<String> columns,
        List<String> maskedColumns,
        String primaryKey,
        List<Map<String, Object>> rows,
        long total,
        int page,
        int pageSize
) {}
