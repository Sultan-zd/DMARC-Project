package com.teknologiia.dmarc.service;

import com.teknologiia.dmarc.dto.ingest.IngestionResult;
import com.teknologiia.dmarc.model.Organization;
import com.teknologiia.dmarc.repository.OrganizationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reports as providers actually send them.
 *
 * <p>Nobody sends a bare XML. Google zips, Microsoft gzips, and both have used
 * filenames the previous version of this code did not recognise — so a report could
 * arrive in the mailbox, be dropped by a filename check, and leave nothing behind
 * saying it had been there.
 *
 * <p>These build the containers rather than reading fixtures, so what is being
 * asserted is visible at the point of the assertion, and each case names the
 * delivery it stands for.
 */
@SpringBootTest
@ActiveProfiles("test")
class CompressedReportIngestionTest {

    @Autowired private ReportIngestionService ingestion;
    @Autowired private OrganizationRepository organizations;

    private Organization organization;

    @BeforeEach
    void seed() {
        organization = organizations.save(Organization.builder()
                .name("Ingest " + UUID.randomUUID().toString().substring(0, 8))
                .build());
    }

    /** A minimal but complete aggregate report, with a unique id per call. */
    private static String reportXml() {
        return reportXml("report-" + UUID.randomUUID());
    }

    private static String reportXml(String reportId) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <feedback>
                  <report_metadata>
                    <org_name>google.com</org_name>
                    <email>noreply-dmarc-support@google.com</email>
                    <report_id>%s</report_id>
                    <date_range><begin>1754438400</begin><end>1754524800</end></date_range>
                  </report_metadata>
                  <policy_published>
                    <domain>example.test</domain>
                    <adkim>r</adkim><aspf>r</aspf><p>reject</p><pct>100</pct>
                  </policy_published>
                  <record>
                    <row>
                      <source_ip>209.85.220.41</source_ip>
                      <count>42</count>
                      <policy_evaluated><disposition>none</disposition>
                        <dkim>pass</dkim><spf>pass</spf></policy_evaluated>
                    </row>
                    <identifiers><header_from>example.test</header_from></identifiers>
                    <auth_results>
                      <dkim><domain>example.test</domain><result>pass</result></dkim>
                      <spf><domain>example.test</domain><result>pass</result></spf>
                    </auth_results>
                  </record>
                </feedback>
                """.formatted(reportId);
    }

    private static byte[] gzip(String content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    private static byte[] zip(String entryName, byte[] content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(content);
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    private IngestionResult ingest(String filename, byte[] content) {
        return ingestion.ingest(organization, filename, new ByteArrayInputStream(content));
    }

    // ─── How the big providers actually deliver ─────────────────────

    @Test
    @DisplayName("Google's zip, named as Google names it")
    void googleZip() throws IOException {
        byte[] archive = zip("google.com!example.test!1754438400!1754524800.xml",
                reportXml().getBytes(StandardCharsets.UTF_8));

        IngestionResult result = ingest(
                "google.com!example.test!1754438400!1754524800.zip", archive);

        assertThat(result.reportsStored()).isEqualTo(1);
        assertThat(result.recordsStored()).isEqualTo(1);
        assertThat(result.errors()).isEmpty();
    }

    @Test
    @DisplayName("Microsoft's gzip, named as Microsoft names it")
    void microsoftGzip() throws IOException {
        IngestionResult result = ingest(
                "enterprise.protection.outlook.com!example.test!1754438400!1754524800.xml.gz",
                gzip(reportXml()));

        assertThat(result.reportsStored()).isEqualTo(1);
        assertThat(result.errors()).isEmpty();
    }

    @Test
    @DisplayName("a zip holding several reports stores every one")
    void zipWithSeveralReports() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (int i = 0; i < 3; i++) {
                zip.putNextEntry(new ZipEntry("report-" + i + ".xml"));
                zip.write(reportXml().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }

        assertThat(ingest("bundle.zip", out.toByteArray()).reportsStored()).isEqualTo(3);
    }

    // ─── The names that used to lose a report ───────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "report.ZIP",              // upper case
            "report.Zip",              // mixed
            "aggregate-report",        // no extension at all
            "report.dat",              // an extension nobody expects
            "report.zip.att",          // a gateway appended its own suffix
    })
    @DisplayName("a zip is read whatever it is called")
    void zipUnderAnyName(String filename) throws IOException {
        byte[] archive = zip("inner.xml", reportXml().getBytes(StandardCharsets.UTF_8));

        // The bytes say PK. Nothing about the name can change that, and the previous
        // version dropped every one of these.
        assertThat(ingest(filename, archive).reportsStored())
                .as("%s should still be recognised as a zip", filename)
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an attachment with no filename at all is still read")
    void noFilename() throws IOException {
        // A Content-Disposition without a filename parameter is legal, and some
        // providers send one. This was the case that vanished most silently: no
        // name meant no extension meant never even opened.
        assertThat(ingest(null, gzip(reportXml())).reportsStored()).isEqualTo(1);
        assertThat(ingest("", zip("r.xml", reportXml().getBytes(StandardCharsets.UTF_8)))
                .reportsStored()).isEqualTo(1);
    }

    @Test
    @DisplayName("an entry inside a zip needs no extension either")
    void zipEntryWithoutExtension() throws IOException {
        byte[] archive = zip("aggregate", reportXml().getBytes(StandardCharsets.UTF_8));

        assertThat(ingest("outer.zip", archive).reportsStored()).isEqualTo(1);
    }

    @Test
    @DisplayName("a gzip inside a zip is opened")
    void gzipInsideZip() throws IOException {
        byte[] archive = zip("report.xml.gz", gzip(reportXml()));

        assertThat(ingest("outer.zip", archive).reportsStored()).isEqualTo(1);
    }

    @Test
    @DisplayName("a zip inside a gzip is opened")
    void zipInsideGzip() throws IOException {
        byte[] inner = zip("report.xml", reportXml().getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(inner);
        }

        assertThat(ingest("odd.gz", out.toByteArray()).reportsStored()).isEqualTo(1);
    }

    @Test
    @DisplayName("a bare XML with a byte order mark is still XML")
    void xmlWithByteOrderMark() {
        byte[] withBom = new byte[3 + reportXml().getBytes(StandardCharsets.UTF_8).length];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        byte[] body = reportXml().getBytes(StandardCharsets.UTF_8);
        System.arraycopy(body, 0, withBom, 3, body.length);

        assertThat(ingest("report.xml", withBom).reportsStored()).isEqualTo(1);
    }

    // ─── Nothing disappears without a trace ─────────────────────────

    @Test
    @DisplayName("an attachment that is not a report is counted, not silently dropped")
    void unrecognisedIsCounted() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 13};

        IngestionResult result = ingest("signature.png", png);

        // Not an error — a covering note or a signature image is normal. But an
        // attachment that is neither stored nor complained about is how "the report
        // never arrived" becomes impossible to investigate.
        assertThat(result.unrecognised()).isEqualTo(1);
        assertThat(result.reportsStored()).isZero();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    @DisplayName("a corrupt archive is reported rather than swallowed")
    void corruptArchiveIsReported() {
        byte[] broken = {'P', 'K', 3, 4, 99, 99, 99, 99, 1, 2, 3};

        IngestionResult result = ingest("truncated.zip", broken);

        assertThat(result.reportsStored()).isZero();
        assertThat(result.errors()).isNotEmpty();
    }

    @Test
    @DisplayName("XML that is not a DMARC report says so")
    void wrongXmlIsAnError() {
        IngestionResult result = ingest("something.xml",
                "<?xml version=\"1.0\"?><invoice><total>10</total></invoice>"
                        .getBytes(StandardCharsets.UTF_8));

        assertThat(result.reportsStored()).isZero();
        assertThat(result.errors()).isNotEmpty();
    }

    @Test
    @DisplayName("the same report twice is stored once")
    void duplicatesAreSkipped() throws IOException {
        String xml = reportXml("fixed-id-" + UUID.randomUUID());

        assertThat(ingest("a.gz", gzip(xml)).reportsStored()).isEqualTo(1);

        IngestionResult again = ingest("b.zip",
                zip("r.xml", xml.getBytes(StandardCharsets.UTF_8)));

        // Same report, different container, different name. What identifies it is
        // the report id inside, so the packaging changes nothing.
        assertThat(again.reportsStored()).isZero();
        assertThat(again.duplicatesSkipped()).isEqualTo(1);
    }

    @Test
    @DisplayName("the run summary says what it could not read, not only what it stored")
    void summaryMentionsWhatWasSkipped() {
        IngestionResult result = new IngestionResult(3, 1, 5, 1, 2,
                java.util.List.of("broken.zip: could not be opened"));

        String summary = EmailService.summarise(result);

        assertThat(summary)
                .contains("1 report(s) imported")
                .contains("1 already known")
                .contains("2 attachment(s) were not reports")
                .contains("could not be read");
    }
}
