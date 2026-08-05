package com.teknologiia.dmarc.service;

import com.itextpdf.kernel.colors.Color;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.io.image.ImageDataFactory;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.borders.Border;
import com.itextpdf.layout.borders.SolidBorder;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Image;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.VerticalAlignment;
import com.itextpdf.layout.properties.UnitValue;
import com.opencsv.CSVWriter;
import com.teknologiia.dmarc.model.DmarcRecord;
import com.teknologiia.dmarc.model.DmarcReport;
import com.teknologiia.dmarc.repository.DmarcReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Builds downloadable exports of stored DMARC data.
 *
 * <p>The CSV is a flat record-per-row dump aimed at analysts who want to pivot the
 * data themselves. The PDF is a summary aimed at readers who want the posture at a
 * glance, so it reports aggregates and the top sending sources rather than every row.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class ExportService {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // ── Teknologiia palette, matching the dashboard ──────────────────────────
    /** Brand green. Used for fills and rules only — see BRAND_DEEP for anything textual. */
    private static final Color BRAND = new DeviceRgb(0x00, 0xAE, 0x4E);
    /**
     * The same green, deepened. White on #00AE4E measures 2.9:1 and #00AE4E on
     * white 2.9:1 — both under the readable threshold — so every element that
     * carries text uses this instead. It still reads as the brand colour.
     */
    private static final Color BRAND_DEEP = new DeviceRgb(0x00, 0x71, 0x3A);
    /** Dark brand surface, as used by the dashboard sidebar and sign-in card. */
    private static final Color INK = new DeviceRgb(0x27, 0x29, 0x2F);
    private static final Color MUTED = new DeviceRgb(0x6B, 0x72, 0x80);
    private static final Color SURFACE = new DeviceRgb(0xEF, 0xF5, 0xF1);
    private static final Color HAIRLINE = new DeviceRgb(0xDD, 0xE4, 0xE0);
    private static final Color PASS = new DeviceRgb(0x00, 0x71, 0x3A);
    private static final Color WARN = new DeviceRgb(0xA3, 0x5A, 0x00);
    private static final Color FAIL = new DeviceRgb(0xC6, 0x28, 0x28);

    private static final String[] CSV_HEADER = {
            "report_id", "org_name", "domain", "date_begin", "date_end", "policy", "sp_policy", "pct",
            "source_ip", "message_count", "disposition", "dkim_result", "spf_result",
            "header_from", "envelope_from", "dkim_domain", "dkim_selector", "spf_domain"
    };

    /** Number of sending sources listed in the PDF summary. */
    private static final int TOP_SOURCES = 15;

    private final DmarcReportRepository reportRepository;

    public byte[] exportCsv(Long organizationId, String domain,
                            LocalDateTime dateFrom, LocalDateTime dateTo) {
        List<DmarcReport> reports =
                reportRepository.findForExport(organizationId, domain, dateFrom, dateTo);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // The BOM lets Excel open the file as UTF-8 instead of guessing the locale codepage.
        out.writeBytes(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});

        try (Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
             CSVWriter csv = new CSVWriter(writer)) {

            csv.writeNext(CSV_HEADER);

            for (DmarcReport report : reports) {
                for (DmarcRecord record : report.getRecords()) {
                    csv.writeNext(new String[]{
                            nullToEmpty(report.getReportId()),
                            nullToEmpty(report.getOrgName()),
                            nullToEmpty(report.getDomain()),
                            format(report.getDateBegin(), TIMESTAMP),
                            format(report.getDateEnd(), TIMESTAMP),
                            nullToEmpty(report.getPolicy()),
                            nullToEmpty(report.getSpPolicy()),
                            report.getPct() == null ? "" : String.valueOf(report.getPct()),
                            nullToEmpty(record.getSourceIp()),
                            String.valueOf(record.getCount()),
                            nullToEmpty(record.getDisposition()),
                            nullToEmpty(record.getDkimResult()),
                            nullToEmpty(record.getSpfResult()),
                            nullToEmpty(record.getHeaderFrom()),
                            nullToEmpty(record.getEnvelopeFrom()),
                            nullToEmpty(record.getDkimDomain()),
                            nullToEmpty(record.getDkimSelector()),
                            nullToEmpty(record.getSpfDomain())
                    });
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build the CSV export", e);
        }

        return out.toByteArray();
    }

    public byte[] exportPdf(Long organizationId, String domain,
                            LocalDateTime dateFrom, LocalDateTime dateTo) {
        List<DmarcReport> reports =
                reportRepository.findForExport(organizationId, domain, dateFrom, dateTo);
        Summary summary = Summary.of(reports);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PdfDocument pdf = new PdfDocument(new PdfWriter(out));
             Document document = new Document(pdf, PageSize.A4)) {

            document.setMargins(40, 40, 40, 40);

            writeHeading(document, domain, dateFrom, dateTo);
            writeSummaryCards(document, summary);
            writeAuthenticationBreakdown(document, summary);
            writeTopSources(document, summary);
            writeFooter(document);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build the PDF export", e);
        }

        return out.toByteArray();
    }

    // ---- PDF sections ------------------------------------------------------

    /**
     * Brand band across the top of the page: the Teknologiia wordmark on the dark
     * surface it was designed for, with the report title beside it.
     */
    private void writeHeading(Document document, String domain, LocalDateTime from, LocalDateTime to) {
        Table band = new Table(UnitValue.createPercentArray(new float[]{1, 2})).useAllAvailableWidth();

        Cell logoCell = new Cell()
                .setBackgroundColor(INK)
                .setBorder(Border.NO_BORDER)
                .setPaddingLeft(18).setPaddingTop(16).setPaddingBottom(16)
                .setVerticalAlignment(VerticalAlignment.MIDDLE);

        // The wordmark is white with transparent counters, which is exactly why the
        // band behind it is dark. If it cannot be loaded the text below still stands.
        loadWordmark().ifPresentOrElse(
                image -> logoCell.add(image.setWidth(122)),
                () -> logoCell.add(new Paragraph("TEKNOLOGIIA")
                        .setFontSize(13).setBold().setFontColor(ColorConstants.WHITE)));

        band.addCell(logoCell);

        band.addCell(new Cell()
                .add(new Paragraph("DMARC Compliance Report")
                        .setFontSize(15).setBold().setFontColor(ColorConstants.WHITE).setMarginBottom(1))
                .add(new Paragraph("Email Security Platform")
                        .setFontSize(7.5f).setFontColor(new DeviceRgb(0xA6, 0xAC, 0xB6))
                        .setCharacterSpacing(1.4f).setMarginTop(0))
                .setBackgroundColor(INK)
                .setBorder(Border.NO_BORDER)
                .setPaddingRight(18).setPaddingTop(16).setPaddingBottom(16)
                .setTextAlignment(TextAlignment.RIGHT)
                .setVerticalAlignment(VerticalAlignment.MIDDLE));

        document.add(band);

        // Brand rule under the band, echoing the bars in the mark.
        document.add(new Table(UnitValue.createPercentArray(new float[]{1})).useAllAvailableWidth()
                .addCell(new Cell().setHeight(3).setBackgroundColor(BRAND).setBorder(Border.NO_BORDER))
                .setMarginBottom(18));

        String scope = (domain == null || domain.isBlank()) ? "All monitored domains" : domain;
        document.add(new Paragraph(scope)
                .setFontSize(13).setBold().setFontColor(INK).setMarginBottom(2));

        String period = "Period: " + (from == null ? "all time" : format(from, DAY))
                + " to " + (to == null ? "today" : format(to, DAY))
                + "   •   Generated " + LocalDateTime.now().format(TIMESTAMP);
        document.add(new Paragraph(period).setFontSize(8.5f).setFontColor(MUTED).setMarginBottom(18));
    }

    /** Reads the wordmark bundled in resources; absent or unreadable is not fatal. */
    private Optional<Image> loadWordmark() {
        try (InputStream in = getClass().getResourceAsStream("/brand/logo-teknologiia.png")) {
            if (in == null) {
                return Optional.empty();
            }
            return Optional.of(new Image(ImageDataFactory.create(in.readAllBytes())));
        } catch (Exception e) {
            log.warn("Brand wordmark unavailable for the PDF export: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private void writeSummaryCards(Document document, Summary summary) {
        Table cards = new Table(UnitValue.createPercentArray(new float[]{1, 1, 1, 1})).useAllAvailableWidth();

        cards.addCell(card("Reports", String.valueOf(summary.reportCount), BRAND_DEEP));
        cards.addCell(card("Messages", String.format(Locale.ENGLISH, "%,d", summary.totalMessages), BRAND_DEEP));
        cards.addCell(card("Compliant", percent(summary.compliantMessages, summary.totalMessages),
                summary.complianceRate() >= 0.95 ? PASS : FAIL));
        cards.addCell(card("Sources", String.valueOf(summary.sourceTotals.size()), BRAND_DEEP));

        document.add(cards.setMarginBottom(20));
    }

    private Cell card(String label, String value, Color accent) {
        return new Cell()
                .add(new Paragraph(label).setFontSize(8).setFontColor(MUTED).setMarginBottom(2))
                .add(new Paragraph(value).setFontSize(17).setBold().setFontColor(accent).setMarginTop(0))
                .setBackgroundColor(SURFACE)
                .setBorder(Border.NO_BORDER)
                .setPadding(10)
                .setMarginRight(4);
    }

    private void writeAuthenticationBreakdown(Document document, Summary summary) {
        document.add(sectionTitle("Authentication Results"));

        Table table = new Table(UnitValue.createPercentArray(new float[]{3, 2, 2})).useAllAvailableWidth();
        table.addHeaderCell(headerCell("Check"));
        table.addHeaderCell(headerCell("Passing messages"));
        table.addHeaderCell(headerCell("Rate"));

        addBreakdownRow(table, "DKIM aligned", summary.dkimPass, summary.totalMessages);
        addBreakdownRow(table, "SPF aligned", summary.spfPass, summary.totalMessages);
        addBreakdownRow(table, "DMARC compliant (DKIM or SPF)", summary.compliantMessages, summary.totalMessages);
        addBreakdownRow(table, "Quarantined or rejected", summary.enforcedMessages,
                summary.totalMessages, false);

        document.add(table.setMarginBottom(20));
    }

    private void addBreakdownRow(Table table, String label, long passing, long total) {
        addBreakdownRow(table, label, passing, total, true);
    }

    /**
     * @param higherIsBetter whether a larger share should read as good. False for
     *                       enforcement figures, where a high number means the
     *                       published policy is doing its job rather than failing.
     */
    private void addBreakdownRow(Table table, String label, long passing, long total,
                                 boolean higherIsBetter) {
        double rate = total == 0 ? 0 : (double) passing / total;
        table.addCell(bodyCell(label, TextAlignment.LEFT));
        table.addCell(bodyCell(String.format(Locale.ENGLISH, "%,d", passing), TextAlignment.RIGHT));

        Cell rateCell = bodyCell(percent(passing, total), TextAlignment.RIGHT);
        if (higherIsBetter) {
            rateCell.setFontColor(rate >= 0.95 ? PASS : rate >= 0.8 ? WARN : FAIL);
        } else {
            rateCell.setFontColor(INK);
        }
        table.addCell(rateCell);
    }

    private void writeTopSources(Document document, Summary summary) {
        document.add(sectionTitle("Top Sending Sources"));

        if (summary.sourceTotals.isEmpty()) {
            document.add(new Paragraph("No records matched the selected filters.")
                    .setFontSize(10).setFontColor(MUTED));
            return;
        }

        Table table = new Table(UnitValue.createPercentArray(new float[]{3, 2, 2, 2})).useAllAvailableWidth();
        table.addHeaderCell(headerCell("Source IP"));
        table.addHeaderCell(headerCell("Messages"));
        table.addHeaderCell(headerCell("Compliant"));
        table.addHeaderCell(headerCell("Rate"));

        summary.sourceTotals.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, SourceTotal> e) -> e.getValue().messages).reversed())
                .limit(TOP_SOURCES)
                .forEach(entry -> {
                    SourceTotal source = entry.getValue();
                    double rate = source.messages == 0 ? 0 : (double) source.compliant / source.messages;

                    table.addCell(bodyCell(entry.getKey(), TextAlignment.LEFT));
                    table.addCell(bodyCell(String.format(Locale.ENGLISH, "%,d", source.messages), TextAlignment.RIGHT));
                    table.addCell(bodyCell(String.format(Locale.ENGLISH, "%,d", source.compliant), TextAlignment.RIGHT));
                    table.addCell(bodyCell(percent(source.compliant, source.messages), TextAlignment.RIGHT)
                            .setFontColor(rate >= 0.95 ? PASS : rate >= 0.8 ? WARN : FAIL));
                });

        document.add(table);

        if (summary.sourceTotals.size() > TOP_SOURCES) {
            document.add(new Paragraph(
                    "Showing the top " + TOP_SOURCES + " of " + summary.sourceTotals.size()
                            + " sources. Export as CSV for the complete set.")
                    .setFontSize(8).setFontColor(MUTED).setMarginTop(6));
        }
    }

    private void writeFooter(Document document) {
        document.add(new Paragraph("Generated by the Teknologiia DMARC Dashboard")
                .setFontSize(8).setFontColor(MUTED)
                .setTextAlignment(TextAlignment.CENTER).setMarginTop(24));
    }

    private Paragraph sectionTitle(String text) {
        return new Paragraph(text).setFontSize(12).setBold()
                .setFontColor(INK).setMarginBottom(6).setMarginTop(4);
    }

    private Cell headerCell(String text) {
        return new Cell()
                .add(new Paragraph(text).setFontSize(9).setBold().setFontColor(ColorConstants.WHITE))
                .setBackgroundColor(INK)
                .setPadding(7)
                .setBorder(Border.NO_BORDER);
    }

    private Cell bodyCell(String text, TextAlignment alignment) {
        return new Cell()
                .add(new Paragraph(text).setFontSize(9))
                .setPadding(5)
                .setTextAlignment(alignment)
                .setBorder(Border.NO_BORDER)
                .setBorderBottom(new SolidBorder(HAIRLINE, 1));
    }

    // ---- Aggregation -------------------------------------------------------

    /** Per-source tally used to rank sending infrastructure in the PDF. */
    private static final class SourceTotal {
        long messages;
        long compliant;
    }

    /**
     * Aggregates a set of reports into the figures the PDF presents.
     *
     * <p>Alignment is read from {@code policy_evaluated}, so "compliant" means the
     * message passed DMARC via DKIM or SPF — which is exactly what determines
     * whether the published policy would let it through.
     */
    private static final class Summary {
        int reportCount;
        long totalMessages;
        long compliantMessages;
        long dkimPass;
        long spfPass;
        long enforcedMessages;
        final Map<String, SourceTotal> sourceTotals = new LinkedHashMap<>();

        static Summary of(List<DmarcReport> reports) {
            Summary summary = new Summary();
            summary.reportCount = reports.size();

            for (DmarcReport report : reports) {
                for (DmarcRecord record : report.getRecords()) {
                    long count = record.getCount();
                    boolean dkim = "pass".equalsIgnoreCase(record.getDkimResult());
                    boolean spf = "pass".equalsIgnoreCase(record.getSpfResult());
                    boolean compliant = dkim || spf;

                    summary.totalMessages += count;
                    if (dkim) summary.dkimPass += count;
                    if (spf) summary.spfPass += count;
                    if (compliant) summary.compliantMessages += count;

                    String disposition = record.getDisposition();
                    if ("quarantine".equalsIgnoreCase(disposition) || "reject".equalsIgnoreCase(disposition)) {
                        summary.enforcedMessages += count;
                    }

                    SourceTotal source = summary.sourceTotals
                            .computeIfAbsent(record.getSourceIp(), ip -> new SourceTotal());
                    source.messages += count;
                    if (compliant) source.compliant += count;
                }
            }
            return summary;
        }

        double complianceRate() {
            return totalMessages == 0 ? 0 : (double) compliantMessages / totalMessages;
        }
    }

    // ---- Formatting --------------------------------------------------------

    private static String percent(long part, long total) {
        return total == 0 ? "—" : String.format(Locale.ENGLISH, "%.1f%%", (double) part / total * 100);
    }

    private static String format(LocalDateTime value, DateTimeFormatter formatter) {
        return value == null ? "" : value.format(formatter);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
