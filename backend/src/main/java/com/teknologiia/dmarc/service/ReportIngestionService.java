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
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReportIngestionService {

    /** Upper bound on a single decompressed document; a real report is well under 1 MB. */
    private static final long MAX_DECOMPRESSED_BYTES = 64L * 1024 * 1024;

    /** Upper bound on documents per archive, to bound the work a single upload can cause. */
    private static final int MAX_ARCHIVE_ENTRIES = 500;

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
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);

        try {
            if (name.endsWith(".zip")) {
                ingestZip(organization, content, accumulator);
            } else if (name.endsWith(".gz") || name.endsWith(".gzip")) {
                store(organization, readBounded(new GZIPInputStream(content), filename), filename, accumulator);
            } else {
                store(organization, readBounded(content, filename), filename, accumulator);
            }
        } catch (IOException e) {
            accumulator.errors.add(filename + ": could not be read (" + e.getMessage() + ")");
        }

        accumulator.filesProcessed++;
        return accumulator.toResult();
    }

    private void ingestZip(Organization organization, InputStream content, Accumulator accumulator)
            throws IOException {
        try (ZipInputStream zip = new ZipInputStream(content)) {
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
                String lower = entryName.toLowerCase(Locale.ROOT);
                if (!lower.endsWith(".xml") && !lower.endsWith(".gz")) {
                    continue;
                }

                try {
                    // The entry stream must not be closed here; that would close the archive.
                    byte[] document = lower.endsWith(".gz")
                            ? readBounded(new GZIPInputStream(zip), entryName)
                            : readBounded(zip, entryName);
                    store(organization, document, entryName, accumulator);
                } catch (IOException e) {
                    accumulator.errors.add(entryName + ": could not be read (" + e.getMessage() + ")");
                }
            }
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
        final List<String> errors = new ArrayList<>();

        IngestionResult toResult() {
            return new IngestionResult(filesProcessed, reportsStored, recordsStored, duplicatesSkipped, errors);
        }
    }
}
