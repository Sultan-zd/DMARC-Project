package com.teknologiia.dmarc.dto.ingest;

import java.util.List;

/**
 * Outcome of an ingestion run.
 *
 * <p>A run can partially succeed: an archive may hold ten reports of which two are
 * malformed. Those are counted in {@code errors} while the rest are still stored.
 *
 * @param unrecognised attachments that were read but were neither XML, gzip nor
 *                     zip — a signature image, a provider's covering note. Not an
 *                     error, and counted anyway: a file that is silently neither
 *                     stored nor complained about is how "the report never arrived"
 *                     becomes impossible to investigate.
 */
public record IngestionResult(
        int filesProcessed,
        int reportsStored,
        int recordsStored,
        int duplicatesSkipped,
        int unrecognised,
        List<String> errors
) {
    public static IngestionResult empty() {
        return new IngestionResult(0, 0, 0, 0, 0, List.of());
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    /** Combines the outcome of two runs, so a multi-file upload reports a single tally. */
    public IngestionResult merge(IngestionResult other) {
        List<String> combined = new java.util.ArrayList<>(errors);
        combined.addAll(other.errors());

        return new IngestionResult(
                filesProcessed + other.filesProcessed(),
                reportsStored + other.reportsStored(),
                recordsStored + other.recordsStored(),
                duplicatesSkipped + other.duplicatesSkipped(),
                unrecognised + other.unrecognised(),
                List.copyOf(combined));
    }
}
