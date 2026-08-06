package com.teknologiia.dmarc.service;

import com.teknologiia.dmarc.dto.database.ColumnInfo;
import com.teknologiia.dmarc.dto.database.TablePage;
import com.teknologiia.dmarc.model.Organization;
import com.teknologiia.dmarc.model.User;
import com.teknologiia.dmarc.repository.OrganizationRepository;
import com.teknologiia.dmarc.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The console's descriptions, held to the schema they describe.
 *
 * <p>Written for the same reason the scoring model has a test: prose about the data
 * drifts from the data silently. A column renamed in an entity takes its description
 * with it and nothing complains — the console simply shows a field with no
 * explanation, which is the state this whole dictionary exists to prevent.
 */
@SpringBootTest
@ActiveProfiles("test")
class SchemaDictionaryTest {

    @Autowired private DataSource dataSource;
    @Autowired private DatabaseConsoleService console;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;

    /** Every {@code table.column} in the live schema, lowercased. */
    private Set<String> liveColumns() throws SQLException {
        Set<String> live = new LinkedHashSet<>();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            for (String table : liveTables()) {
                try (ResultSet columns = metadata.getColumns(
                        connection.getCatalog(), connection.getSchema(), table, "%")) {
                    while (columns.next()) {
                        live.add(table.toLowerCase(Locale.ROOT) + "."
                                + columns.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        return live;
    }

    private List<String> liveTables() throws SQLException {
        List<String> names = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             ResultSet result = connection.getMetaData().getTables(
                     connection.getCatalog(), connection.getSchema(), "%", new String[]{"TABLE"})) {
            while (result.next()) {
                names.add(result.getString("TABLE_NAME"));
            }
        }
        return names;
    }

    @Test
    @DisplayName("every described column still exists")
    void describedColumnsExist() throws SQLException {
        Set<String> live = liveColumns();

        assertThat(SchemaDictionary.documentedColumns())
                .as("a description whose column was renamed still reads as authoritative")
                .allSatisfy(documented -> assertThat(live).contains(documented));
    }

    @Test
    @DisplayName("every column named as leading still exists")
    void leadingColumnsExist() throws SQLException {
        Set<String> live = liveColumns();

        // These decide what is visible without scrolling. A stale name here does not
        // error — the column simply stops leading, silently.
        assertThat(SchemaDictionary.documentedLeading())
                .allSatisfy(documented -> assertThat(live).contains(documented));
    }

    @Test
    @DisplayName("every described table still exists")
    void describedTablesExist() throws SQLException {
        Set<String> live = new LinkedHashSet<>();
        liveTables().forEach(t -> live.add(t.toLowerCase(Locale.ROOT)));

        assertThat(SchemaDictionary.documentedTables()).allSatisfy(
                documented -> assertThat(live).contains(documented));
    }

    @Test
    @DisplayName("every table in the schema is described")
    void everyTableIsDescribed() throws SQLException {
        // The other direction: a table added without a description lists as "Other"
        // and tells the operator nothing. Cheap to write, easy to forget.
        assertThat(liveTables())
                .allSatisfy(table -> assertThat(SchemaDictionary.table(table).label())
                        .as("%s has no entry in the dictionary", table)
                        .isNotNull());
    }

    @Test
    @DisplayName("every column of every table is described")
    void everyColumnIsDescribed() throws SQLException {
        assertThat(liveColumns())
                .allSatisfy(column -> {
                    String[] parts = column.split("\\.", 2);
                    assertThat(SchemaDictionary.columnDescription(parts[0], parts[1]))
                            .as("%s has no entry in the dictionary", column)
                            .isNotNull();
                });
    }

    // ─── Reading a table ────────────────────────────────────────────

    @Test
    @DisplayName("columns arrive labelled, typed and described")
    void columnsCarryTheirMeaning() {
        TablePage page = console.rows("users", 1, 5, null, false, "operator");

        ColumnInfo role = column(page, "role");
        assertThat(role.label()).isEqualTo("Role");
        assertThat(role.kind()).isEqualTo(ColumnInfo.TEXT);
        assertThat(role.description()).contains("ADMIN");

        // bit(1) in MariaDB, boolean in H2 — the interface must not have to care.
        assertThat(column(page, "is_active").kind()).isEqualTo(ColumnInfo.BOOLEAN);
        assertThat(column(page, "created_at").kind()).isEqualTo(ColumnInfo.DATE);
        assertThat(column(page, "id").kind()).isEqualTo(ColumnInfo.NUMBER);

        assertThat(column(page, "id").primaryKey()).isTrue();
        assertThat(column(page, "hashed_password").credential()).isTrue();
        assertThat(column(page, "username").credential()).isFalse();
    }

    @Test
    @DisplayName("columns are ordered for reading, not as the engine stored them")
    void columnsAreOrderedForReading() {
        List<String> order = console.rows("users", 1, 1, null, false, "operator")
                .columns().stream().map(c -> c.name().toLowerCase(Locale.ROOT)).toList();

        // Key, then what an account actually is, then what it belongs to, then the
        // rest. Physical order put the password hash fifth and the username tenth.
        assertThat(order.subList(0, 4)).containsExactly("id", "username", "email", "role");

        // Links sit after the substance, dates after the links.
        assertThat(order.indexOf("organization_id")).isLessThan(order.indexOf("is_active"));
        assertThat(order.indexOf("is_active")).isLessThan(order.indexOf("created_at"));

        // Masked columns last: nothing is learned from where they sit. Which of the
        // two comes first is the engine's business, so only the group is asserted.
        assertThat(order.subList(order.size() - 2, order.size()))
                .containsExactlyInAnyOrder("hashed_password", "totp_secret");
    }

    @Test
    @DisplayName("the column a table exists for is visible without scrolling")
    void theInterestingColumnLeads() {
        List<String> order = console.rows("dmarc_records", 1, 1, null, false, "operator")
                .columns().stream().map(c -> c.name().toLowerCase(Locale.ROOT)).toList();

        // Physical order put source_ip eighth of twelve, which on a grid this wide
        // means the address doing the sending was off the right-hand edge.
        assertThat(order.subList(0, 5))
                .containsExactly("id", "source_ip", "count", "header_from", "disposition");
    }

    @Test
    @DisplayName("a foreign key is labelled by the name it points at")
    void foreignKeysResolveToNames() {
        String name = "Dictionary " + UUID.randomUUID().toString().substring(0, 8);
        Organization organization = organizationRepository.save(
                Organization.builder().name(name).build());
        User user = userRepository.save(User.builder()
                .organization(organization)
                .username("dict-" + UUID.randomUUID().toString().substring(0, 8))
                .email(UUID.randomUUID() + "@dictionary.test")
                .hashedPassword("$2a$10$notarealhashbutlongenoughtolooklikeone")
                .role("VIEWER")
                .active(true)
                .build());

        TablePage page = console.rows("users", 1, 200, user.getUsername(), false, "operator");

        ColumnInfo link = column(page, "organization_id");
        assertThat(link.referencesTable()).isEqualToIgnoringCase("organizations");
        // Read as "Organization", not "Organization id": the value beside it is a name.
        assertThat(link.label()).isEqualTo("Organization");

        assertThat(page.references().get(link.name()))
                .as("the tenant identifier should be resolvable to its name in one query")
                .containsValue(name);
    }

    @Test
    @DisplayName("names are derived readably when the dictionary has no entry")
    void derivesReadableNames() {
        assertThat(SchemaDictionary.humanise("last_run_summary")).isEqualTo("Last run summary");
        assertThat(SchemaDictionary.humanise("dkim_result")).isEqualTo("DKIM result");
        assertThat(SchemaDictionary.humanise("source_ip")).isEqualTo("Source IP");
        assertThat(SchemaDictionary.humanise("domain")).isEqualTo("Domain");
    }

    private ColumnInfo column(TablePage page, String name) {
        return page.columns().stream()
                .filter(c -> c.name().equalsIgnoreCase(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No column " + name + " in " + page.table()));
    }
}
