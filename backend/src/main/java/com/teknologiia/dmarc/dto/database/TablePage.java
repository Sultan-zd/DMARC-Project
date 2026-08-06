package com.teknologiia.dmarc.dto.database;

import java.util.List;
import java.util.Map;

/**
 * A page of rows from one table, with enough about the table to read them.
 *
 * @param primaryKey     the column a row can be deleted by, or null when the table
 *                       has none and rows cannot be addressed individually
 * @param maskedColumns  columns returned as bullets unless explicitly revealed:
 *                       password hashes, encrypted secrets, tokens. Present in the
 *                       list so the interface can say what it is hiding
 * @param references     resolved foreign keys, as {@code column -> (key -> label)}.
 *                       A row saying {@code organization_id 7} is true and useless;
 *                       this is what lets the interface show the organization's name
 *                       beside it without a second request per row
 */
public record TablePage(
        String table,
        String label,
        String description,
        List<ColumnInfo> columns,
        List<String> maskedColumns,
        String primaryKey,
        List<Map<String, Object>> rows,
        Map<String, Map<String, String>> references,
        long total,
        int page,
        int pageSize
) {}
