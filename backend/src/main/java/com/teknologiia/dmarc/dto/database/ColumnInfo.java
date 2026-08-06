package com.teknologiia.dmarc.dto.database;

/**
 * One column, described well enough to be read by someone who did not write it.
 *
 * <p>A database client shows {@code organization_id bigint(20) NOT NULL} and stops
 * there, which is enough when you already hold the schema in your head. This console
 * is read by whoever is on call, so each column also carries what it is for and what
 * an unusual value in it would mean.
 *
 * @param kind            how the value should be rendered — the SQL type says
 *                        {@code datetime(6)} and {@code bit(1)}, which the interface
 *                        would otherwise print raw
 * @param referencesTable the table this column points into, or null. Set from the
 *                        live foreign keys, never guessed from the name, so a
 *                        column called {@code report_id} that happens to hold a
 *                        provider's own string identifier is not mistaken for a link
 */
public record ColumnInfo(
        String name,
        String label,
        String description,
        String kind,
        String sqlType,
        boolean nullable,
        boolean primaryKey,
        boolean credential,
        String referencesTable
) {
    /** Rendering families, chosen from the JDBC type rather than the column name. */
    public static final String NUMBER = "number";
    public static final String TEXT = "text";
    public static final String DATE = "date";
    public static final String BOOLEAN = "boolean";
    public static final String JSON = "json";
}
