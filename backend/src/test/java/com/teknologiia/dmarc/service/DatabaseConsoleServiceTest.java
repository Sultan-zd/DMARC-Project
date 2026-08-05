package com.teknologiia.dmarc.service;

import com.teknologiia.dmarc.model.Organization;
import com.teknologiia.dmarc.model.User;
import com.teknologiia.dmarc.repository.OrganizationRepository;
import com.teknologiia.dmarc.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Direct database access from the operator console.
 *
 * <p>Table and column names cannot be bound as query parameters, so they are
 * interpolated into SQL — which is exactly the shape of an injection. What makes it
 * safe is that the interpolated name never comes from the request: it is matched
 * against the live schema first and the schema's own spelling is what is used.
 * These tests exist to keep that true.
 */
@SpringBootTest
@ActiveProfiles("test")
class DatabaseConsoleServiceTest {

    @Autowired private DatabaseConsoleService console;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;

    private String username;

    @BeforeEach
    void seed() {
        username = "dbc-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        Organization organization = organizationRepository.save(
                Organization.builder().name("DbConsole " + username).build());
        userRepository.save(User.builder()
                .organization(organization)
                .username(username)
                .email(username + "@console.test")
                .hashedPassword("$2a$10$notarealhashbutlongenoughtolooklikeone")
                .role("ADMIN")
                .active(true)
                .build());
    }

    // ─── The injection surface ──────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "users; DROP TABLE users",
            "users WHERE 1=1",
            "users`--",
            "information_schema.tables",
            "../users",
            "'",
            "no_such_table",
    })
    @DisplayName("a name that is not a real table never reaches SQL")
    void refusesNamesThatAreNotTables(String attempt) {
        assertThatThrownBy(() -> console.rows(attempt, 1, 10, null, false, username))
                .isInstanceOf(ResponseStatusException.class);

        // The point of the test: users is still there afterwards.
        assertThat(userRepository.findByUsername(username)).isPresent();
    }

    @Test
    @DisplayName("a blank or missing table name is refused")
    void refusesBlankNames() {
        assertThatThrownBy(() -> console.rows("", 1, 10, null, false, username))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> console.rows(null, 1, 10, null, false, username))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("a search term is bound, not concatenated")
    void searchIsBound() {
        // Were this interpolated, the quote alone would break the statement.
        var page = console.rows("users", 1, 10, "' OR '1'='1", false, username);

        assertThat(page.rows()).isEmpty();
        assertThat(userRepository.findByUsername(username)).isPresent();
    }

    // ─── Reading ────────────────────────────────────────────────────

    @Test
    @DisplayName("lists the tables with their row counts")
    void listsTables() {
        assertThat(console.tables())
                .extracting(t -> t.name().toLowerCase())
                .contains("users", "organizations");
        assertThat(console.tables())
                .filteredOn(t -> t.name().equalsIgnoreCase("users"))
                .allMatch(t -> t.rows() > 0);
    }

    @Test
    @DisplayName("credential columns are bullets until revealed")
    void credentialsAreMaskedByDefault() {
        var masked = console.rows("users", 1, 200, username, false, username);

        // Column-name casing belongs to the engine; read it back rather than assume.
        String column = masked.maskedColumns().stream()
                .filter(c -> c.equalsIgnoreCase("hashed_password")).findFirst().orElseThrow();

        assertThat(masked.rows()).isNotEmpty();
        assertThat(masked.rows()).allSatisfy(row ->
                assertThat(row.get(column)).isEqualTo("••••••••"));
    }

    @Test
    @DisplayName("revealing shows them, because a database client would")
    void credentialsCanBeRevealed() {
        var revealed = console.rows("users", 1, 200, username, true, username);

        String column = revealed.maskedColumns().stream()
                .filter(c -> c.equalsIgnoreCase("hashed_password")).findFirst().orElseThrow();

        assertThat(revealed.rows()).isNotEmpty();
        assertThat(revealed.rows().get(0).get(column)).asString().startsWith("$2a$");
    }

    @Test
    @DisplayName("a page never exceeds the cap, however large the request")
    void pageSizeIsCapped() {
        assertThat(console.rows("users", 1, 100_000, null, false, username).pageSize())
                .isLessThanOrEqualTo(200);
    }

    // ─── Writing ────────────────────────────────────────────────────

    @Test
    @DisplayName("the tables holding accounts cannot be emptied in one action")
    void protectedTablesRefuseToBeCleared() {
        // Clearing users would remove the operator too, leaving nobody able to sign
        // in and undo it.
        assertThatThrownBy(() -> console.clearTable("users", username))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("every account including yours");

        assertThatThrownBy(() -> console.clearTable("organizations", username))
                .isInstanceOf(ResponseStatusException.class);

        assertThat(userRepository.findByUsername(username)).isPresent();
    }

    @Test
    @DisplayName("one row can still be deleted by its key")
    void deletesOneRow() {
        Long id = userRepository.findByUsername(username).orElseThrow().getId();

        console.deleteRow("users", String.valueOf(id), "operator");

        assertThat(userRepository.findByUsername(username)).isEmpty();
    }

    @Test
    @DisplayName("deleting a key that matches nothing says so rather than passing silently")
    void deletingAnUnknownKeyIsReported() {
        assertThatThrownBy(() -> console.deleteRow("users", "-1", "operator"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("No row with that key");
    }

    @Test
    @DisplayName("an unprotected table can be emptied")
    void clearsAnUnprotectedTable() {
        // alerts holds nothing that cannot be regenerated, and nothing anybody signs
        // in with — the sort of table this action exists for.
        assertThat(console.clearTable("alerts", "operator")).isGreaterThanOrEqualTo(0);
    }
}
