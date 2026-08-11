package com.teknologiia.dmarc.service;

import com.teknologiia.dmarc.dto.ingest.IngestionResult;
import com.teknologiia.dmarc.model.DmarcReport;
import com.teknologiia.dmarc.model.Organization;
import com.teknologiia.dmarc.repository.DmarcReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Turns uploaded DMARC report files into stored reports.
 *
 * <p>Mailbox providers ship aggregate reports as a bare {@code .xml}, a gzipped
 * {@code .xml.gz}, or a {@code .zip} holding one or more documents, so all three
 * are accepted. Reports already known by their {@code report_id} are skipped
 * rather than duplicated.
 *
 * <p>The container is identified from the file's first bytes, not its name — see
 * {@link ReportFormat}. Providers disagree on extensions and some send none at
 * all, so dispatching on the name meant a report could arrive in the mailbox, be
 * unreadable to the application, and leave no trace of either fact.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReportIngestionService {

    /** Upper bound on a single decompressed document; a real report is well under 1 MB. */
    private static final long MAX_DECOMPRESSED_BYTES = 64L * 1024 * 1024;

    /** Upper bound on documents per archive, to bound the work a single upload can cause. */
    private static final int MAX_ARCHIVE_ENTRIES = 500;

    /** A report is a document, or a document in one container. Never deeper than this. */
    private static final int MAX_NESTING = 4;

    private final DmarcParserService parser;
    private final DmarcReportRepository reportRepository;

    /**
     * Ingests one uploaded file, transparently unwrapping gzip and zip containers.
     *
     * @param filename original name, used only to pick the container format
     */
    @Transactional
    public IngestionResult ingest(Organization organization, String filename, InputStream content) {
        Accumulator accumulator = new Accumulator();
        String label = filename == null || filename.isBlank() ? "(unnamed attachment)" : filename;

        try {
            // Read whole rather than streamed: the format has to be known before the
            // stream can be wrapped, and a report is small enough that holding it
            // costs nothing. readBounded is what keeps that true.
            byte[] raw = readBounded(content, label);
            unwrap(organization, raw, label, accumulator, 0);
        } catch (IOException e) {
            accumulator.errors.add(label + ": could not be read (" + e.getMessage() + ")");
        }

        accumulator.filesProcessed++;
        return accumulator.toResult();
    }

    /**
     * Opens whatever container the bytes turn out to be, then stores what is inside.
     *
     * @param depth guards against an archive nested inside an archive inside an
     *              archive. A real report is never nested more than once, and without
     *              a bound a crafted file could recurse until the stack ends.
     */
    private void unwrap(Organization organization, byte[] raw, String label,
                        Accumulator accumulator, int depth) {
        if (depth > MAX_NESTING) {
            accumulator.errors.add(label + ": archives nested more than "
                    + MAX_NESTING + " deep are not opened");
            return;
        }

        switch (ReportFormat.of(raw, label)) {
            case ZIP -> ingestZip(organization, raw, label, accumulator, depth);
            case GZIP -> {
                try {
                    byte[] inner = readBounded(
                            new GZIPInputStream(new ByteArrayInputStream(raw)), label);
                    // Recursive rather than parsed straight away: a .gz occasionally
                    // holds a .zip, and a provider doing that is not doing anything
                    // the application should refuse.
                    unwrap(organization, inner, label, accumulator, depth + 1);
                } catch (IOException e) {
                    accumulator.errors.add(label + ": could not be decompressed ("
                            + e.getMessage() + ")");
                }
            }
            case XML -> store(organization, raw, label, accumulator);
            case UNKNOWN -> {
                // Counted, not discarded. An attachment that is neither a report nor
                // an error is exactly the case that used to vanish without trace, and
                // leaving no record of it is how "the report never arrived" becomes
                // impossible to investigate.
                accumulator.unrecognised++;
                log.debug("Attachment {} is neither XML, gzip nor zip; skipped", label);
            }
        }
    }

    private void ingestZip(Organization organization, byte[] archive, String label,
                           Accumulator accumulator, int depth) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            int entries = 0;

            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                if (++entries > MAX_ARCHIVE_ENTRIES) {
                    accumulator.errors.add(
                            "Archive holds more than " + MAX_ARCHIVE_ENTRIES + " files; the rest were skipped");
                    return;
                }

                String entryName = entry.getName();
                try {
                    // The entry stream must not be closed here; that would close the
                    // archive. Sniffed like everything else, so an entry named
                    // `report` with no extension is still read.
                    byte[] document = readBounded(zip, entryName);
                    unwrap(organization, document, entryName, accumulator, depth + 1);
                } catch (IOException e) {
                    accumulator.errors.add(entryName + ": could not be read (" + e.getMessage() + ")");
                }
            }

            // An archive whose bytes said PK but which yielded nothing is damaged.
            // ZipInputStream does not throw for that — it returns no entries, which
            // is indistinguishable from an empty archive and reads as success. That
            // silence is the failure worth catching: the report was there, it was
            // not stored, and nothing anywhere said so.
            if (entries == 0) {
                accumulator.errors.add(label + ": looks like a zip but holds no readable "
                        + "files — it is most likely truncated or corrupt");
            }
        } catch (IOException e) {
            accumulator.errors.add(label + ": archive could not be opened (" + e.getMessage() + ")");
        }
    }

    private void store(Organization organization, byte[] document, String label, Accumulator accumulator) {
        try {
            DmarcReport report = parser.parse(new ByteArrayInputStream(document));
            report.setOrganization(organization);

            // Scoped to this organization: another tenant holding the same provider
            // report must not make this one look like a duplicate.
            if (reportRepository.existsByOrganizationIdAndReportId(
                    organization.getId(), report.getReportId())) {
                accumulator.duplicatesSkipped++;
                log.debug("Skipping already-stored DMARC report {}", report.getReportId());
                return;
            }

            reportRepository.save(report);
            accumulator.reportsStored++;
            accumulator.recordsStored += report.getRecords().size();
            log.info("Stored DMARC report {} for {} ({} records)",
                    report.getReportId(), report.getDomain(), report.getRecords().size());
        } catch (DmarcParseException e) {
            accumulator.errors.add(label + ": " + e.getMessage());
            log.warn("Rejected DMARC document {}: {}", label, e.getMessage());
        }
    }

    /**
     * Reads a stream fully, refusing anything past {@link #MAX_DECOMPRESSED_BYTES}.
     * A small archive can otherwise expand into an unbounded amount of memory.
     */
    private byte[] readBounded(InputStream in, String label) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;

        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > MAX_DECOMPRESSED_BYTES) {
                throw new IOException(label + " expands beyond the "
                        + (MAX_DECOMPRESSED_BYTES / (1024 * 1024)) + " MB limit");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /** Mutable tally, converted to the immutable {@link IngestionResult} at the end of a run. */
    private static final class Accumulator {
        int filesProcessed;
        int reportsStored;
        int recordsStored;
        int duplicatesSkipped;
        int unrecognised;
        final List<String> errors = new ArrayList<>();

        IngestionResult toResult() {
            return new IngestionResult(filesProcessed, reportsStored, recordsStored,
                    duplicatesSkipped, unrecognised, errors);
        }
    }
}
