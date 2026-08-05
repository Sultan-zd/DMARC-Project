package com.teknologiia.dmarc.dto.database;

/**
 * One table, as the console lists it.
 *
 * @param rows       current row count
 * @param sizeKb     data and index size together
 * @param protectedTable whether clearing it is refused — the tables that hold
 *                       accounts and organizations, whose loss cannot be undone
 *                       from inside the application
 */
public record TableSummary(String name, long rows, long sizeKb, boolean protectedTable) {}
