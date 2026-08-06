package com.teknologiia.dmarc.dto.database;

/**
 * One table, as the console lists it.
 *
 * @param label      a readable name — {@code dmarc_records} is what the schema calls
 *                   it, "Sending sources" is what it holds
 * @param group      the heading it is listed under, so eleven tables read as three
 *                   groups rather than one alphabetical wall
 * @param rows       current row count
 * @param sizeKb     data and index size together
 * @param protectedTable whether clearing it is refused — the tables that hold
 *                       accounts and organizations, whose loss cannot be undone
 *                       from inside the application
 */
public record TableSummary(
        String name,
        String label,
        String group,
        String description,
        long rows,
        long sizeKb,
        boolean protectedTable
) {}
