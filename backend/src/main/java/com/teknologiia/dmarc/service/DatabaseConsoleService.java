package com.teknologiia.dmarc.service;

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

    private static final int MAX_PAGE_SIZE = 200;

    private final DataSource dataSource;

    /** Every table in this schema, with what it holds. */
    public List<TableSummary> tables() {
        List<TableSummary> tables = new ArrayList<>();

        try (Connection connection = dataSource.getConnection()) {
            Map<String, Long> sizes = sizesKb(connection);

            for (String name : tableNames(connection)) {
                // Counted rather than estimated: information_schema.table_rows is an
                // approximation on InnoDB, and an administrator comparing it against
                // the listing below would notice the two disagreeing.
                tables.add(new TableSummary(name, countRows(connection, name),
                        sizes.getOrDefault(name.toLowerCase(Locale.ROOT), 0L),
                        PROTECTED.contains(name.toLowerCase(Locale.ROOT))));
            }
        } catch (SQLException e) {
            throw failure("Could not list the tables", e);
        }
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
            List<String> columns = columnsOf(connection, table);
            String primaryKey = primaryKeyOf(connection, table);

            List<String> masked = columns.stream().filter(this::isCredential).toList();

            // Search spans every column, as a database client's filter would. Values
            // are still bound; only the identifiers come from the verified schema.
            String where = "";
            if (search != null && !search.isBlank()) {
                StringJoiner clauses = new StringJoiner(" OR ");
                // CONCAT rather than CAST: `CAST(x AS CHAR)` means the full string
                // in MariaDB but CHAR(1) in H2, so a search matched nothing under
                // test while working in production — the worst way round.
                columns.forEach(c -> clauses.add("CONCAT(`" + c + "`, '') LIKE ?"));
                where = " WHERE " + clauses;
            }

            if (reveal && !masked.isEmpty()) {
                log.warn("Operator {} revealed credential columns {} in {}", operator, masked, table);
            }

            long total = count(connection, table, where, columns.size(), search);
            List<Map<String, Object>> data = new ArrayList<>();

            String sql = "SELECT * FROM `" + table + "`" + where
                    + (primaryKey != null ? " ORDER BY `" + primaryKey + "` DESC" : "")
                    + " LIMIT ? OFFSET ?";

            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int index = bindSearch(statement, columns.size(), search);
                statement.setInt(index++, size);
                statement.setInt(index, offset);

                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (String column : columns) {
                            boolean hide = !reveal && isCredential(column);
                            row.put(column, hide
                                    ? mask(result.getString(column))
                                    : readable(result.getObject(column)));
                        }
                        data.add(row);
                    }
                }
            }

            return new TablePage(table, columns, masked, primaryKey, data, total,
                    Math.max(page, 1), size);

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

    private List<String> columnsOf(Connection connection, String table) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (ResultSet result = connection.getMetaData()
                .getColumns(connection.getCatalog(), connection.getSchema(), table, "%")) {
            while (result.next()) {
                columns.add(result.getString("COLUMN_NAME"));
            }
        }
        return columns;
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

    /** Dates, numbers and text as they are; anything exotic as its own text form. */
    private Object readable(Object value) {
        if (value == null || value instanceof Number || value instanceof String
                || value instanceof Boolean) {
            return value;
        }
        return value.toString();
    }

    private ResponseStatusException failure(String what, SQLException e) {
        log.error("{}: {}", what, e.getMessage());
        return new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                what + ": " + e.getMessage());
    }
}
