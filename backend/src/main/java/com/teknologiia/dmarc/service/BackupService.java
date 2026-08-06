package com.teknologiia.dmarc.service;

import com.teknologiia.dmarc.dto.platform.BackupStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Taking a copy of the database, and being honest about whether there is one.
 *
 * <p>Until this existed the database volume was the only copy of everything the
 * product had ever collected, and nothing anywhere made a second one. A disk fault,
 * a mistaken {@code docker compose down -v}, or a bad migration and every client's
 * reports were gone for good.
 *
 * <p>This runs {@code mariadb-dump} from inside the application rather than from a
 * sidecar container on a cron. The sidecar is tidier in principle and worse in
 * practice: backups rarely fail loudly, they stop — and a schedule nobody can see
 * the results of stops silently. Running them here means {@link #status()} can put
 * the age of the newest one on a page an operator already looks at, and turn it red
 * when it grows. That visibility is what keeps backups working, far more than where
 * the process runs.
 *
 * <p><strong>A dump is the whole database in one file</strong>: password hashes,
 * encrypted mailbox credentials, every tenant's reports. It is written with
 * owner-only permissions and can only be downloaded by an operator, and that
 * download is recorded.
 */
@Service
@Slf4j
public class BackupService {

    /** {@code dmarc-backup-2026-08-06T21-15-00.sql.gz} — sorts chronologically as text. */
    private static final DateTimeFormatterHolder NAME = new DateTimeFormatterHolder();
    private static final Pattern JDBC_MARIADB = Pattern.compile(
            "jdbc:(?:mariadb|mysql)://([^:/?]+)(?::(\\d+))?/([^?]+)");

    /** A dump that has not finished in this long is not going to. */
    private static final int TIMEOUT_MINUTES = 30;

    private final String directory;
    private final int keepCount;
    private final int intervalHours;
    private final String jdbcUrl;
    private final String dbUsername;
    private final String dbPassword;
    private final AuditService auditService;

    /** Set when the last attempt failed, so the page can show why rather than a gap. */
    private volatile String lastError;
    private volatile boolean running;

    public BackupService(
            @Value("${app.backup.directory:}") String directory,
            @Value("${app.backup.keep:14}") int keepCount,
            @Value("${app.backup.interval-hours:24}") int intervalHours,
            @Value("${spring.datasource.url}") String jdbcUrl,
            @Value("${spring.datasource.username}") String dbUsername,
            @Value("${spring.datasource.password:}") String dbPassword,
            AuditService auditService) {
        this.directory = directory == null ? "" : directory.trim();
        this.keepCount = Math.max(keepCount, 1);
        this.intervalHours = Math.max(intervalHours, 1);
        this.jdbcUrl = jdbcUrl;
        this.dbUsername = dbUsername;
        this.dbPassword = dbPassword;
        this.auditService = auditService;
    }

    public boolean isConfigured() {
        return !directory.isBlank();
    }

    /**
     * Whether {@code mariadb-dump} is on this image.
     *
     * <p>It is in the container and usually is not on a developer's machine. Saying
     * so plainly beats a scheduled run that fails every night into a log.
     */
    public boolean isToolAvailable() {
        try {
            Process process = new ProcessBuilder("mariadb-dump", "--version")
                    .redirectErrorStream(true).start();
            return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    // ─── Taking one ─────────────────────────────────────────────────

    /**
     * The scheduled run.
     *
     * <p>Delayed at startup so a restart loop cannot produce a backup per crash, and
     * so the first one does not compete with the application coming up.
     */
    @Scheduled(
            initialDelayString = "${app.backup.initial-delay-ms:300000}",
            fixedDelayString = "#{${app.backup.interval-hours:24} * 60 * 60 * 1000}")
    public void scheduled() {
        if (!isConfigured()) {
            return;
        }
        try {
            Path written = run(AuditService.SYSTEM);
            log.info("Scheduled backup written to {}", written.getFileName());
        } catch (Exception e) {
            // Already recorded in lastError and surfaced on the Platform page.
            log.error("Scheduled backup failed: {}", e.getMessage());
        }
    }

    /**
     * Takes a backup now.
     *
     * @param actor who asked, for the audit trail
     * @return the file written
     */
    public synchronized Path run(String actor) {
        if (!isConfigured()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No backup directory is configured. Set BACKUP_DIR.");
        }
        if (running) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A backup is already running.");
        }

        running = true;
        try {
            Path folder = Paths.get(directory);
            Files.createDirectories(folder);

            Target target = parseJdbcUrl(jdbcUrl);
            Path file = folder.resolve("dmarc-backup-" + NAME.now() + ".sql.gz");
            // resolve, not resolveSibling: the latter would put the working file
            // beside the backup directory rather than inside it, and the atomic move
            // below would then be a cross-directory rename.
            Path partial = folder.resolve(file.getFileName() + ".partial");

            // Written under a temporary name and moved into place only once
            // mariadb-dump has exited cleanly. A dump interrupted halfway is a file
            // that looks like a backup and restores into a half-empty database —
            // the worst possible outcome, because it is discovered at restore time.
            List<String> command = new ArrayList<>(List.of(
                    "mariadb-dump",
                    "--host=" + target.host(),
                    "--port=" + target.port(),
                    "--user=" + dbUsername,
                    // Consistent across tables without locking anything out: InnoDB
                    // gives the dump one transaction's view while writes continue.
                    "--single-transaction",
                    "--quick",
                    "--routines",
                    "--events",
                    "--default-character-set=utf8mb4",
                    target.database()));

            ProcessBuilder builder = new ProcessBuilder(command);
            // Passed in the environment rather than on the command line: arguments
            // are readable by anything that can list processes on the host.
            if (dbPassword != null && !dbPassword.isBlank()) {
                builder.environment().put("MYSQL_PWD", dbPassword);
            }
            builder.redirectErrorStream(false);

            long bytes = writeCompressed(builder, partial);

            Files.move(partial, file, StandardCopyOption.ATOMIC_MOVE);
            restrictPermissions(file);

            int removed = applyRetention(folder);
            lastError = null;

            auditService.record(actor, AuditAction.BACKUP_TAKEN, AuditAction.TARGET_BACKUP,
                    null, file.getFileName().toString(),
                    humanBytes(bytes) + (removed > 0 ? ", " + removed + " older removed" : ""));

            log.info("Backup written: {} ({})", file.getFileName(), humanBytes(bytes));
            return file;

        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            lastError = e.getMessage();
            log.error("Backup failed", e);
            auditService.record(actor, AuditAction.BACKUP_FAILED, AuditAction.TARGET_BACKUP,
                    null, null, e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Backup failed: " + e.getMessage());
        } finally {
            running = false;
        }
    }

    /** Runs the dump, gzipping its output as it arrives. Returns the bytes written. */
    private long writeCompressed(ProcessBuilder builder, Path destination) throws Exception {
        Process process = builder.start();

        StringBuilder errors = new StringBuilder();
        Thread drain = new Thread(() -> {
            try (var reader = process.errorReader()) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // Bounded: a dump that fails on every table would otherwise
                    // build a string the size of the schema.
                    if (errors.length() < 2000) {
                        errors.append(line).append('\n');
                    }
                }
            } catch (IOException ignored) {
                // The process ended; whatever it managed to say is in errors.
            }
        });
        drain.setDaemon(true);
        drain.start();

        try (var input = process.getInputStream();
             var output = Files.newOutputStream(destination,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
             var gzip = new java.util.zip.GZIPOutputStream(output, 16 * 1024)) {
            input.transferTo(gzip);
        }

        if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            Files.deleteIfExists(destination);
            throw new IllegalStateException("mariadb-dump did not finish within "
                    + TIMEOUT_MINUTES + " minutes");
        }
        drain.join(5_000);

        if (process.exitValue() != 0) {
            Files.deleteIfExists(destination);
            throw new IllegalStateException("mariadb-dump exited " + process.exitValue()
                    + (errors.isEmpty() ? "" : ": " + errors.toString().trim()));
        }

        long size = Files.size(destination);
        if (size == 0) {
            Files.deleteIfExists(destination);
            throw new IllegalStateException("mariadb-dump produced an empty file");
        }
        return size;
    }

    /** Keeps the newest {@code keepCount} and deletes the rest. */
    private int applyRetention(Path folder) throws IOException {
        List<Path> backups = listBackups(folder);
        if (backups.size() <= keepCount) {
            return 0;
        }
        int removed = 0;
        for (Path old : backups.subList(keepCount, backups.size())) {
            try {
                Files.deleteIfExists(old);
                removed++;
            } catch (IOException e) {
                log.warn("Could not remove old backup {}: {}", old.getFileName(), e.getMessage());
            }
        }
        return removed;
    }

    // ─── Reading the state ──────────────────────────────────────────

    public BackupStatus status() {
        if (!isConfigured()) {
            return new BackupStatus(false, isToolAvailable(), false, null, 0, 0L,
                    null, null, keepCount, intervalHours, lastError, List.of());
        }

        try {
            Path folder = Paths.get(directory);
            List<Path> backups = Files.isDirectory(folder) ? listBackups(folder) : List.of();

            long total = 0;
            List<BackupStatus.Entry> recent = new ArrayList<>();
            for (Path path : backups) {
                long size = Files.size(path);
                total += size;
                if (recent.size() < 10) {
                    recent.add(new BackupStatus.Entry(
                            path.getFileName().toString(), modifiedAt(path), size));
                }
            }

            LocalDateTime newest = backups.isEmpty() ? null : modifiedAt(backups.get(0));
            Long ageHours = newest == null ? null
                    : Duration.between(newest, LocalDateTime.now(ZoneOffset.UTC)).toHours();

            // Twice the interval before it counts as unhealthy: one missed run is a
            // restart or a slow night, two is a pattern.
            boolean healthy = ageHours != null && ageHours <= (long) intervalHours * 2;

            return new BackupStatus(true, isToolAvailable(), healthy, directory,
                    backups.size(), total, newest, ageHours, keepCount, intervalHours,
                    lastError, recent);

        } catch (IOException e) {
            return new BackupStatus(true, isToolAvailable(), false, directory, 0, 0L,
                    null, null, keepCount, intervalHours,
                    "Could not read the backup directory: " + e.getMessage(), List.of());
        }
    }

    /** One backup by name, for download. The name is matched, never interpolated. */
    public Path find(String name) {
        if (!isConfigured()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No backups.");
        }
        try {
            for (Path path : listBackups(Paths.get(directory))) {
                if (path.getFileName().toString().equals(name)) {
                    return path;
                }
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not read the backup directory.");
        }
        // A name that is not one of the files we just listed never reaches the
        // filesystem, so "../../etc/passwd" is a 404 rather than a traversal.
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such backup.");
    }

    /** Newest first. */
    private List<Path> listBackups(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (var stream = Files.list(folder)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".sql.gz"))
                    .sorted(Comparator.comparing(BackupService::modifiedAtQuietly).reversed())
                    .toList();
        }
    }

    // ─── Helpers ────────────────────────────────────────────────────

    record Target(String host, String port, String database) {}

    /** Pulls host, port and database out of the JDBC URL the application already has. */
    static Target parseJdbcUrl(String url) {
        Matcher matcher = JDBC_MARIADB.matcher(url == null ? "" : url);
        if (!matcher.find()) {
            throw new IllegalStateException(
                    "Backups need a MariaDB or MySQL connection; this one is " + url);
        }
        return new Target(matcher.group(1),
                matcher.group(2) == null ? "3306" : matcher.group(2),
                matcher.group(3));
    }

    /**
     * Owner-only, where the filesystem supports it.
     *
     * <p>A dump is every password hash and every tenant's reports in one file. On
     * Windows this quietly does nothing, which is why it is not the only control —
     * the directory is inside a container and the download needs an operator.
     */
    private static void restrictPermissions(Path file) {
        try {
            Files.setPosixFilePermissions(file,
                    java.util.Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException e) {
            log.debug("Could not restrict permissions on {}: {}", file.getFileName(), e.getMessage());
        }
    }

    private static LocalDateTime modifiedAt(Path path) throws IOException {
        return LocalDateTime.ofInstant(Files.getLastModifiedTime(path).toInstant(), ZoneOffset.UTC);
    }

    private static FileTime modifiedAtQuietly(Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException e) {
            return FileTime.fromMillis(0);
        }
    }

    public static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /** Kept in a holder so the pattern is compiled once and the class stays readable. */
    private static final class DateTimeFormatterHolder {
        private final java.time.format.DateTimeFormatter formatter =
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss");

        String now() {
            return LocalDateTime.now(ZoneOffset.UTC).format(formatter);
        }
    }
}
