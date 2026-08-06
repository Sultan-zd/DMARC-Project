package com.teknologiia.dmarc.service;

import com.teknologiia.dmarc.dto.platform.BackupStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The parts of backing up that can be wrong without anybody noticing.
 *
 * <p>Not the dump itself — that is {@code mariadb-dump}, which does not need
 * testing and is not present on a developer's machine anyway. What is tested is
 * everything around it: whether the status is honest about there being no backups,
 * whether an ageing one is reported as ageing, and whether a name from a request
 * can reach a file it should not.
 *
 * <p>That last one matters most. The download endpoint takes a filename from the
 * URL, and a path that is joined rather than matched is a traversal.
 */
class BackupServiceTest {

    private BackupService service(Path directory, int keep, int intervalHours) {
        return new BackupService(
                directory == null ? "" : directory.toString(),
                keep, intervalHours,
                "jdbc:mariadb://db:3306/dmarc_dashboard", "dmarc", "secret",
                new NoOpAudit());
    }

    private Path writeBackup(Path directory, String name, Instant modified) throws IOException {
        Path file = directory.resolve(name);
        Files.writeString(file, "-- a dump\n");
        Files.setLastModifiedTime(file, FileTime.from(modified));
        return file;
    }

    // ─── Saying so when there is nothing ────────────────────────────

    @Test
    @DisplayName("with no directory configured it says so rather than looking healthy")
    void unconfiguredIsNotHealthy() {
        BackupStatus status = service(null, 14, 24).status();

        assertThat(status.configured()).isFalse();
        assertThat(status.healthy())
                .as("no backups must never read as fine")
                .isFalse();
        assertThat(status.count()).isZero();
    }

    @Test
    @DisplayName("an empty directory is configured but not healthy")
    void emptyDirectoryIsNotHealthy(@TempDir Path directory) {
        BackupStatus status = service(directory, 14, 24).status();

        assertThat(status.configured()).isTrue();
        assertThat(status.count()).isZero();
        assertThat(status.healthy()).isFalse();
        assertThat(status.ageHours()).isNull();
    }

    // ─── Age ────────────────────────────────────────────────────────

    @Test
    @DisplayName("a recent backup is healthy")
    void recentIsHealthy(@TempDir Path directory) throws IOException {
        writeBackup(directory, "dmarc-backup-2026-08-06T10-00-00.sql.gz",
                Instant.now().minus(2, ChronoUnit.HOURS));

        BackupStatus status = service(directory, 14, 24).status();

        assertThat(status.healthy()).isTrue();
        assertThat(status.count()).isEqualTo(1);
        assertThat(status.ageHours()).isBetween(1L, 3L);
    }

    @Test
    @DisplayName("one past twice the interval is not")
    void staleIsUnhealthy(@TempDir Path directory) throws IOException {
        // The failure this exists for: backups do not usually break loudly, they
        // stop. Nobody notices until the day one is needed, unless a page says so.
        writeBackup(directory, "dmarc-backup-2026-08-01T10-00-00.sql.gz",
                Instant.now().minus(3, ChronoUnit.DAYS));

        BackupStatus status = service(directory, 14, 24).status();

        assertThat(status.count()).isEqualTo(1);
        assertThat(status.healthy())
                .as("a three-day-old backup on a daily schedule is a stopped schedule")
                .isFalse();
    }

    @Test
    @DisplayName("one missed run is tolerated, two are not")
    void toleratesASingleMissedRun(@TempDir Path directory) throws IOException {
        writeBackup(directory, "dmarc-backup-a.sql.gz", Instant.now().minus(30, ChronoUnit.HOURS));
        assertThat(service(directory, 14, 24).status().healthy())
                .as("a restart or a slow night should not raise an alarm")
                .isTrue();
    }

    @Test
    @DisplayName("the newest is the one reported, whatever the names")
    void reportsTheNewest(@TempDir Path directory) throws IOException {
        // Sorted by modification time, not by filename: a restored or copied file
        // carries whatever name it was given.
        writeBackup(directory, "zzz-old.sql.gz", Instant.now().minus(5, ChronoUnit.DAYS));
        writeBackup(directory, "aaa-new.sql.gz", Instant.now().minus(1, ChronoUnit.HOURS));

        BackupStatus status = service(directory, 14, 24).status();

        assertThat(status.count()).isEqualTo(2);
        assertThat(status.ageHours()).isLessThan(3);
        assertThat(status.recent().get(0).name()).isEqualTo("aaa-new.sql.gz");
    }

    @Test
    @DisplayName("only completed dumps are counted")
    void ignoresPartialsAndStrays(@TempDir Path directory) throws IOException {
        // A dump in progress carries .partial until mariadb-dump exits cleanly. It
        // is not a backup and must not make the status look healthy.
        Files.writeString(directory.resolve("dmarc-backup-now.sql.gz.partial"), "half a dump");
        Files.writeString(directory.resolve("notes.txt"), "not a backup");

        assertThat(service(directory, 14, 24).status().count()).isZero();
    }

    // ─── The download surface ───────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "../../../etc/passwd",
            "..\\..\\windows\\win.ini",
            "/etc/shadow",
            "dmarc-backup-nope.sql.gz",
            "",
    })
    @DisplayName("a name that is not one of the files listed reaches no file at all")
    void refusesNamesThatAreNotBackups(String attempt, @TempDir Path directory) throws IOException {
        writeBackup(directory, "dmarc-backup-real.sql.gz", Instant.now());

        // The name is compared against the directory listing, never joined onto a
        // path. A traversal therefore matches nothing rather than escaping.
        assertThatThrownBy(() -> service(directory, 14, 24).find(attempt))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("a real backup is found by its name")
    void findsARealBackup(@TempDir Path directory) throws IOException {
        writeBackup(directory, "dmarc-backup-real.sql.gz", Instant.now());

        assertThat(service(directory, 14, 24).find("dmarc-backup-real.sql.gz"))
                .exists();
    }

    // ─── Reading the connection ─────────────────────────────────────

    @Test
    @DisplayName("host, port and database come out of the JDBC URL the app already has")
    void parsesTheJdbcUrl() {
        var target = BackupService.parseJdbcUrl(
                "jdbc:mariadb://db:3306/dmarc_dashboard?useUnicode=true&characterEncoding=utf8");

        assertThat(target.host()).isEqualTo("db");
        assertThat(target.port()).isEqualTo("3306");
        assertThat(target.database())
                .as("the query string must not end up in the database name")
                .isEqualTo("dmarc_dashboard");
    }

    @Test
    @DisplayName("a URL with no port gets the default")
    void defaultsThePort() {
        assertThat(BackupService.parseJdbcUrl("jdbc:mysql://localhost/dmarc").port())
                .isEqualTo("3306");
    }

    @Test
    @DisplayName("a database this cannot dump is refused rather than half-attempted")
    void refusesAnUnsupportedUrl() {
        // H2 under test, for instance. Better to say so than to run mariadb-dump
        // against something that will never answer.
        assertThatThrownBy(() -> BackupService.parseJdbcUrl("jdbc:h2:mem:dmarc-test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MariaDB or MySQL");
    }

    @Test
    @DisplayName("running with no directory configured is refused, not silently skipped")
    void refusesToRunUnconfigured() {
        assertThatThrownBy(() -> service(null, 14, 24).run("operator"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("BACKUP_DIR");
    }

    /** The audit trail has its own tests; this one only needs it not to be null. */
    private static final class NoOpAudit extends AuditService {
        NoOpAudit() {
            super(null, null);
        }

        @Override
        public void record(String actor, Long organizationId, String action, String targetType,
                           Long targetId, String targetLabel, String detail) {
            // Deliberately nothing.
        }
    }
}
