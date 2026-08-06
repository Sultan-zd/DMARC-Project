package com.teknologiia.dmarc.service;

import com.teknologiia.dmarc.dto.database.ColumnInfo;
import com.teknologiia.dmarc.dto.database.TablePage;
import com.teknologiia.dmarc.dto.database.TableSummary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/**
 * Direct read and write over this application's own schema.
 *
 * <p>The operator already has all of this through a database client; nothing here
 * grants a power that did not exist. What it changes is reach — the database
 * listens on localhost, this page is published — so every destructive call
 * re-checks the operator's password and every one is logged with their name.
 *
 * <p>Table and column names cannot be bound as parameters, so they are never taken
 * from the request as written. Each one is matched against the live schema first
 * and the matched name is what reaches the statement. A name that is not a real
 * table is refused before any SQL is built, which is what keeps a crafted table
 * name from becoming a crafted query.
 *
 * <p>What it adds over a database client is meaning: {@link SchemaDictionary}
 * supplies what each table and column is for, the live foreign keys are resolved so
 * a tenant identifier reads as a name, and each column carries the type family the
 * interface needs to render it. A console nobody can read without the schema in
 * their head is a console that gets used carelessly.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DatabaseConsoleService {

    /**
     * Tables the console will not empty in one action.
     *
     * <p>Rows can still be deleted one at a time, and the higher-level actions for
     * removing an account or an organization still work. What this stops is a
     * single click that erases everyone — including the operator, who would then
     * have no way back in.
     */
    private static final Set<String> PROTECTED = Set.of("users", "organizations");

    /**
     * Columns returned as bullets unless explicitly revealed.
     *
     * <p>A bcrypt hash sitting in a local database is a different proposition from
     * one travelling over the internet, where it can be taken away and attacked at
     * leisure. Revealing them is a deliberate, logged act.
     */
    private static final Set<String> CREDENTIAL_COLUMNS = Set.of(
            "hashed_password", "password_cipher", "totp_secret", "code_hash", "token",
            "verification_token");

    /**
     * How a referenced row is named, most specific first.
     *
     * <p>Only used to label a foreign key on screen. The name comes from the
     * referenced table's own columns as the schema reports them, so a table without
     * any of these simply shows its key unresolved.
     */
    private static final List<String> LABEL_COLUMNS =
            List.of("name", "username", "domain", "email", "report_id");

    private static final int MAX_PAGE_SIZE = 200;

    private final DataSource dataSource;

    /** Every table in this schema, with what it holds and what it is for. */
    public List<TableSummary> tables() {
        List<TableSummary> tables = new ArrayList<>();

        try (Connection connection = dataSource.getConnection()) {
            Map<String, Long> sizes = sizesKb(connection);

            for (String name : tableNames(connection)) {
                SchemaDictionary.TableDoc doc = SchemaDictionary.table(name);
                // Counted rather than estimated: information_schema.table_rows is an
                // approximation on InnoDB, and an administrator comparing it against
                // the listing below would notice the two disagreeing.
                tables.add(new TableSummary(name,
                        SchemaDictionary.tableLabel(name),
                        doc.group(),
                        doc.description(),
                        countRows(connection, name),
                        sizes.getOrDefault(name.toLowerCase(Locale.ROOT), 0L),
                        PROTECTED.contains(name.toLowerCase(Locale.ROOT))));
            }
        } catch (SQLException e) {
            throw failure("Could not list the tables", e);
        }

        // Grouped as the reader thinks about them, then by the name shown rather
        // than the name stored — "Accounts" belongs above "Claimed email domains",
        // which sorting on `users` and `organization_domains` would not give.
        tables.sort(Comparator
                .comparingInt((TableSummary t) -> SchemaDictionary.groupRank(t.group()))
                .thenComparing(TableSummary::label, String.CASE_INSENSITIVE_ORDER));
        return tables;
    }

    /** Table names from JDBC metadata, which every engine answers the same way. */
    private List<String> tableNames(Connection connection) throws SQLException {
        List<String> names = new ArrayList<>();
        try (ResultSet result = connection.getMetaData().getTables(
                connection.getCatalog(), connection.getSchema(), "%", new String[]{"TABLE"})) {
            while (result.next()) {
                names.add(result.getString("TABLE_NAME"));
            }
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /**
     * On-disk size per table, where the engine exposes it.
     *
     * <p>MariaDB and MySQL do; H2 under test does not, and an absent figure is
     * better than failing the whole listing over a decoration.
     */
    private Map<String, Long> sizesKb(Connection connection) {
        Map<String, Long> sizes = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT LOWER(table_name), COALESCE(ROUND((data_length + index_length) / 1024), 0)
                FROM information_schema.tables
                WHERE table_schema = DATABASE()
                """);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                sizes.put(result.getString(1), result.getLong(2));
            }
        } catch (SQLException e) {
            log.debug("Table sizes unavailable on this engine: {}", e.getMessage());
        }
        return sizes;
    }

    /**
     * @param reveal show credential columns in clear. Deliberate, and logged:
     *               parity with a database client is the point, but a hash
     *               leaving the machine should leave a trace behind it.
     */
    public TablePage rows(String requestedTable, int page, int pageSize, String search,
                          boolean reveal, String operator) {
        int size = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        int offset = Math.max(page - 1, 0) * size;

        try (Connection connection = dataSource.getConnection()) {
            String table = requireTable(connection, requestedTable);
            List<ColumnInfo> columns = describeColumns(connection, table);
            List<String> names = columns.stream().map(ColumnInfo::name).toList();
            String primaryKey = primaryKeyOf(connection, table);

            List<String> masked = columns.stream()
                    .filter(ColumnInfo::credential).map(ColumnInfo::name).toList();

            // Search spans every column, as a database client's filter would. Values
            // are still bound; only the identifiers come from the verified schema.
            String where = "";
            if (search != null && !search.isBlank()) {
                StringJoiner clauses = new StringJoiner(" OR ");
                // CONCAT rather than CAST: `CAST(x AS CHAR)` means the full string
                // in MariaDB but CHAR(1) in H2, so a search matched nothing under
                // test while working in production — the worst way round.
                names.forEach(c -> clauses.add("CONCAT(`" + c + "`, '') LIKE ?"));
                where = " WHERE " + clauses;
            }

            if (reveal && !masked.isEmpty()) {
                log.warn("Operator {} revealed credential columns {} in {}", operator, masked, table);
            }

            long total = count(connection, table, where, names.size(), search);
            List<Map<String, Object>> data = new ArrayList<>();

            String sql = "SELECT * FROM `" + table + "`" + where
                    + (primaryKey != null ? " ORDER BY `" + primaryKey + "` DESC" : "")
                    + " LIMIT ? OFFSET ?";

            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int index = bindSearch(statement, names.size(), search);
                statement.setInt(index++, size);
                statement.setInt(index, offset);

                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (String column : names) {
                            boolean hide = !reveal && isCredential(column);
                            row.put(column, hide
                                    ? mask(result.getString(column))
                                    : readable(result.getObject(column)));
                        }
                        data.add(row);
                    }
                }
            }

            SchemaDictionary.TableDoc doc = SchemaDictionary.table(table);
            return new TablePage(table, SchemaDictionary.tableLabel(table), doc.description(),
                    columns, masked, primaryKey, data,
                    resolveReferences(connection, columns, data),
                    total, Math.max(page, 1), size);

        } catch (SQLException e) {
            throw failure("Could not read that table", e);
        }
    }

    /** Deletes one row, addressed by its primary key. */
    public void deleteRow(String requestedTable, String key, String operator) {
        try (Connection connection = dataSource.getConnection()) {
            String table = requireTable(connection, requestedTable);
            String primaryKey = primaryKeyOf(connection, table);
            if (primaryKey == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "This table has no primary key, so a single row cannot be addressed.");
            }

            String sql = "DELETE FROM `" + table + "` WHERE `" + primaryKey + "` = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, key);
                int removed = statement.executeUpdate();
                log.warn("Operator {} deleted {} row(s) from {} where {}={}",
                        operator, removed, table, primaryKey, key);
                if (removed == 0) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No row with that key.");
                }
            }
        } catch (SQLException e) {
            throw failure("Could not delete that row", e);
        }
    }

    /** Empties a table completely. Refused on the ones holding accounts. */
    public long clearTable(String requestedTable, String operator) {
        try (Connection connection = dataSource.getConnection()) {
            String table = requireTable(connection, requestedTable);
            if (PROTECTED.contains(table.toLowerCase(Locale.ROOT))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Emptying " + table + " in one action is not available — it would remove "
                                + "every account including yours. Delete rows individually, or "
                                + "remove organizations from the console above.");
            }

            // DELETE rather than TRUNCATE: TRUNCATE ignores foreign keys and would
            // leave rows in other tables pointing at nothing.
            try (Statement statement = connection.createStatement()) {
                long removed = statement.executeUpdate("DELETE FROM `" + table + "`");
                log.warn("Operator {} emptied table {} — {} row(s) removed", operator, table, removed);
                return removed;
            }
        } catch (SQLException e) {
            throw failure("Could not empty that table", e);
        }
    }

    // ─── Describing the schema ──────────────────────────────────────

    /**
     * Every column with its type, its constraints, and what it is for.
     *
     * <p>The structural half comes from JDBC metadata and is therefore always true
     * of the database in front of us; only the prose comes from the dictionary. A
     * column added later still appears, correctly typed, merely undescribed.
     */
    private List<ColumnInfo> describeColumns(Connection connection, String table)
            throws SQLException {
        Map<String, String> foreignKeys = foreignKeys(connection, table);
        String primaryKey = primaryKeyOf(connection, table);
        List<ColumnInfo> columns = new ArrayList<>();

        try (ResultSet result = connection.getMetaData()
                .getColumns(connection.getCatalog(), connection.getSchema(), table, "%")) {
            while (result.next()) {
                String name = result.getString("COLUMN_NAME");
                String references = foreignKeys.get(name.toLowerCase(Locale.ROOT));
                int jdbcType = result.getInt("DATA_TYPE");

                columns.add(new ColumnInfo(
                        name,
                        SchemaDictionary.columnLabel(table, name, references),
                        SchemaDictionary.columnDescription(table, name),
                        kindOf(jdbcType, table, name),
                        sqlType(result),
                        DatabaseMetaData.columnNullable == result.getInt("NULLABLE"),
                        name.equalsIgnoreCase(primaryKey),
                        isCredential(name),
                        references));
            }
        }
        return orderForReading(table, columns);
    }

    /**
     * Puts the columns in the order a person reads them.
     *
     * <p>Physical order is an accident of how the entity was written and how the
     * engine laid it out — the same table came back with the password hash in the
     * fifth column and the username in the tenth. So the grid leads with the key,
     * then the columns {@link SchemaDictionary} names as the point of the table,
     * then its links, then the rest, with dates after the substance and the masked
     * columns last, where they cost nothing to scroll past.
     *
     * <p>Ties keep their schema order, so this rearranges the groups without
     * shuffling within them.
     */
    private List<ColumnInfo> orderForReading(String table, List<ColumnInfo> columns) {
        return columns.stream()
                .sorted(Comparator
                        .comparingInt((ColumnInfo c) -> readingWeight(table, c))
                        .thenComparingInt(c -> SchemaDictionary.leadingRank(table, c.name())))
                .toList();
    }

    private int readingWeight(String table, ColumnInfo column) {
        if (column.primaryKey()) {
            return 0;
        }
        // Bullets, whatever they hold. Nothing is learned from their position.
        if (column.credential()) {
            return 5;
        }
        if (SchemaDictionary.isLeading(table, column.name())) {
            return 1;
        }
        if (column.referencesTable() != null) {
            return 2;
        }
        // Dates sit after the substance: useful, rarely the thing being looked for.
        return ColumnInfo.DATE.equals(column.kind()) ? 4 : 3;
    }

    /** {@code varchar(255)}, {@code bigint}, {@code datetime(6)} — as the engine names it. */
    private String sqlType(ResultSet columnMetadata) throws SQLException {
        String type = columnMetadata.getString("TYPE_NAME");
        int size = columnMetadata.getInt("COLUMN_SIZE");
        boolean sized = type != null
                && (type.toLowerCase(Locale.ROOT).contains("char") || type.equalsIgnoreCase("varchar"));
        return sized && size > 0 ? type.toLowerCase(Locale.ROOT) + "(" + size + ")"
                : String.valueOf(type).toLowerCase(Locale.ROOT);
    }

    /**
     * The rendering family for a column.
     *
     * <p>Taken from the JDBC type rather than the column name, so a column called
     * {@code report_id} holding a provider's string identifier is not formatted as a
     * number. MariaDB reports {@code bit(1)} as BIT and H2 reports the same column as
     * BOOLEAN — both land on the same family here, which is the point.
     */
    private String kindOf(int jdbcType, String table, String column) {
        if (SchemaDictionary.isJson(table, column)) {
            return ColumnInfo.JSON;
        }
        return switch (jdbcType) {
            case Types.BIT, Types.BOOLEAN -> ColumnInfo.BOOLEAN;
            case Types.DATE, Types.TIME, Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE
                    -> ColumnInfo.DATE;
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
                 Types.DECIMAL, Types.NUMERIC, Types.DOUBLE, Types.FLOAT, Types.REAL
                    -> ColumnInfo.NUMBER;
            default -> ColumnInfo.TEXT;
        };
    }

    /** Foreign keys as {@code column -> referenced table}, from the live constraints. */
    private Map<String, String> foreignKeys(Connection connection, String table) {
        Map<String, String> keys = new HashMap<>();
        try (ResultSet result = connection.getMetaData()
                .getImportedKeys(connection.getCatalog(), connection.getSchema(), table)) {
            while (result.next()) {
                keys.put(result.getString("FKCOLUMN_NAME").toLowerCase(Locale.ROOT),
                        result.getString("PKTABLE_NAME"));
            }
        } catch (SQLException e) {
            // A schema without declared constraints still reads; it simply shows
            // identifiers unresolved rather than failing the page.
            log.debug("Foreign keys unavailable for {}: {}", table, e.getMessage());
        }
        return keys;
    }

    /**
     * Turns the identifiers on this page into names.
     *
     * <p>One query per referenced table for the whole page, not one per row: a page
     * of 200 records referencing 200 reports would otherwise be 200 round trips.
     * Identifiers are bound; only the table and column names are interpolated, and
     * those came from the metadata rather than from the request.
     */
    private Map<String, Map<String, String>> resolveReferences(
            Connection connection, List<ColumnInfo> columns, List<Map<String, Object>> rows) {

        Map<String, Map<String, String>> resolved = new LinkedHashMap<>();
        if (rows.isEmpty()) {
            return resolved;
        }

        for (ColumnInfo column : columns) {
            if (column.referencesTable() == null) {
                continue;
            }
            Set<String> keys = new LinkedHashSet<>();
            for (Map<String, Object> row : rows) {
                Object value = row.get(column.name());
                if (value != null) {
                    keys.add(String.valueOf(value));
                }
            }
            if (keys.isEmpty()) {
                continue;
            }
            Map<String, String> labels = labelsFor(connection, column.referencesTable(), keys);
            if (!labels.isEmpty()) {
                resolved.put(column.name(), labels);
            }
        }
        return resolved;
    }

    private Map<String, String> labelsFor(Connection connection, String table, Set<String> keys) {
        Map<String, String> labels = new LinkedHashMap<>();
        try {
            String actual = requireTable(connection, table);
            String primaryKey = primaryKeyOf(connection, actual);
            String labelColumn = labelColumnOf(connection, actual);
            if (primaryKey == null || labelColumn == null) {
                return labels;
            }

            String placeholders = String.join(",", Collections.nCopies(keys.size(), "?"));
            String sql = "SELECT `" + primaryKey + "`, `" + labelColumn + "` FROM `" + actual
                    + "` WHERE `" + primaryKey + "` IN (" + placeholders + ")";

            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int index = 1;
                for (String key : keys) {
                    statement.setString(index++, key);
                }
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        labels.put(result.getString(1), result.getString(2));
                    }
                }
            }
        } catch (SQLException | ResponseStatusException e) {
            log.debug("Could not label references into {}: {}", table, e.getMessage());
        }
        return labels;
    }

    /** The column that names a row in the referenced table, if it has one. */
    private String labelColumnOf(Connection connection, String table) throws SQLException {
        List<String> present = new ArrayList<>();
        try (ResultSet result = connection.getMetaData()
                .getColumns(connection.getCatalog(), connection.getSchema(), table, "%")) {
            while (result.next()) {
                present.add(result.getString("COLUMN_NAME"));
            }
        }
        for (String candidate : LABEL_COLUMNS) {
            for (String column : present) {
                if (column.equalsIgnoreCase(candidate)) {
                    return column;
                }
            }
        }
        return null;
    }

    // ─── Helpers ────────────────────────────────────────────────────

    /**
     * Matches a requested name against the live schema.
     *
     * <p>The returned value is the schema's own spelling, never the caller's. That
     * is the whole defence: an identifier cannot be bound as a parameter, so the
     * only safe one to interpolate is one the database just told us exists.
     */
    private String requireTable(Connection connection, String requested) throws SQLException {
        if (requested == null || requested.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No table named.");
        }
        for (String actual : tableNames(connection)) {
            if (actual.equalsIgnoreCase(requested)) {
                return actual;
            }
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such table.");
    }

    private String primaryKeyOf(Connection connection, String table) throws SQLException {
        try (ResultSet result = connection.getMetaData()
                .getPrimaryKeys(connection.getCatalog(), connection.getSchema(), table)) {
            return result.next() ? result.getString("COLUMN_NAME") : null;
        }
    }

    private long countRows(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM `" + table + "`")) {
            return result.next() ? result.getLong(1) : 0L;
        }
    }

    private long count(Connection connection, String table, String where,
                       int columnCount, String search) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM `" + table + "`" + where)) {
            bindSearch(statement, columnCount, search);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0L;
            }
        }
    }

    private int bindSearch(PreparedStatement statement, int columnCount, String search)
            throws SQLException {
        if (search == null || search.isBlank()) {
            return 1;
        }
        String pattern = "%" + search.trim() + "%";
        for (int i = 1; i <= columnCount; i++) {
            statement.setString(i, pattern);
        }
        return columnCount + 1;
    }

    /** Case-insensitive: column-name casing is the engine's choice, not ours. */
    private boolean isCredential(String column) {
        return CREDENTIAL_COLUMNS.contains(column.toLowerCase(Locale.ROOT));
    }

    private String mask(String value) {
        return value == null ? null : "••••••••";
    }

    /**
     * Dates, numbers and text as they are; anything exotic as its own text form.
     *
     * <p>{@code byte[]} is handled explicitly because MariaDB can answer a
     * {@code bit(1)} that way, and its {@code toString} is an object identity —
     * which is how a boolean column comes to display as {@code [B@1f3a0c}.
     */
    private Object readable(Object value) {
        if (value == null || value instanceof Number || value instanceof String
                || value instanceof Boolean) {
            return value;
        }
        if (value instanceof byte[] bytes) {
            return bytes.length == 1 ? bytes[0] != 0 : Base64.getEncoder().encodeToString(bytes);
        }
        return value.toString();
    }

    private ResponseStatusException failure(String what, SQLException e) {
        log.error("{}: {}", what, e.getMessage());
        return new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                what + ": " + e.getMessage());
    }
}
