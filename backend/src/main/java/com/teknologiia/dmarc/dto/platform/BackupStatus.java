package com.teknologiia.dmarc.dto.platform;

import java.time.LocalDateTime;
import java.util.List;

/**
 * What the backups look like right now.
 *
 * @param configured   whether a backup directory is set at all. False is not an
 *                     error — it is how the application runs outside a container —
 *                     but it means there are no backups, and the page says so
 * @param healthy      the newest backup is younger than the interval allows. The
 *                     figure that matters: backups do not usually fail loudly, they
 *                     stop, and nobody notices until the day they are needed
 * @param ageHours     how old the newest one is, or null when there are none
 * @param toolAvailable whether mariadb-dump is on this image. Without it nothing
 *                      can be taken, and saying so beats a run that fails silently
 */
public record BackupStatus(
        boolean configured,
        boolean toolAvailable,
        boolean healthy,
        String directory,
        int count,
        long totalBytes,
        LocalDateTime newestAt,
        Long ageHours,
        int keepCount,
        int intervalHours,
        String lastError,
        List<Entry> recent
) {
    public record Entry(String name, LocalDateTime at, long bytes) {}
}
