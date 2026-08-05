package com.teknologiia.dmarc.service;

import com.teknologiia.dmarc.model.DmarcRecord;
import com.teknologiia.dmarc.model.DmarcReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.Month;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DmarcParserServiceTest {

    private final DmarcParserService parser = new DmarcParserService();

    private DmarcReport parseSample() {
        InputStream xml = getClass().getResourceAsStream("/dmarc/sample-report.xml");
        assertThat(xml).as("sample report fixture").isNotNull();
        return parser.parse(xml);
    }

    @Test
    @DisplayName("reads report metadata and the published policy")
    void readsMetadataAndPolicy() {
        DmarcReport report = parseSample();

        assertThat(report.getReportId()).isEqualTo("18453760000000000001");
        assertThat(report.getOrgName()).isEqualTo("google.com");
        assertThat(report.getOrgEmail()).isEqualTo("noreply-dmarc-support@google.com");
        assertThat(report.getDomain()).isEqualTo("teknologiia.com");
        assertThat(report.getPolicy()).isEqualTo("quarantine");
        assertThat(report.getSpPolicy()).isEqualTo("quarantine");
        assertThat(report.getAdkim()).isEqualTo("r");
        assertThat(report.getAspf()).isEqualTo("r");
        assertThat(report.getPct()).isEqualTo(100);
    }

    @Test
    @DisplayName("converts the Unix date range to UTC")
    void convertsDateRange() {
        DmarcReport report = parseSample();

        assertThat(report.getDateBegin())
                .isEqualTo(LocalDateTime.of(2024, Month.JULY, 29, 0, 0, 0));
        assertThat(report.getDateEnd())
                .isEqualTo(LocalDateTime.of(2024, Month.JULY, 29, 23, 59, 59));
    }

    @Test
    @DisplayName("reads every record and links it back to the report")
    void readsRecords() {
        DmarcReport report = parseSample();

        assertThat(report.getRecords()).hasSize(2);
        assertThat(report.getRecords()).allSatisfy(record ->
                assertThat(record.getReport()).isSameAs(report));

        DmarcRecord passing = report.getRecords().get(0);
        assertThat(passing.getSourceIp()).isEqualTo("209.85.220.41");
        assertThat(passing.getCount()).isEqualTo(42);
        assertThat(passing.getDisposition()).isEqualTo("none");
        assertThat(passing.getHeaderFrom()).isEqualTo("teknologiia.com");
        assertThat(passing.getDkimSelector()).isEqualTo("google");
    }

    @Test
    @DisplayName("takes alignment from policy_evaluated and domains from auth_results")
    void distinguishesAlignmentFromAuthResults() {
        // <dkim> and <spf> appear under both policy_evaluated and auth_results. The
        // alignment verdict must come from the former, the domains from the latter.
        DmarcRecord failing = parseSample().getRecords().get(1);

        assertThat(failing.getSpfResult()).isEqualTo("fail");   // policy_evaluated, not "softfail"
        assertThat(failing.getSpfDomain()).isEqualTo("spoofer.example");
        assertThat(failing.getDisposition()).isEqualTo("quarantine");
        assertThat(failing.getDkimResult()).isEqualTo("fail");
        assertThat(failing.getEnvelopeFrom()).isNull();
    }

    @Test
    @DisplayName("refuses documents that declare external entities")
    void refusesExternalEntities() {
        String xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE feedback [<!ENTITY payload SYSTEM "file:///etc/passwd">]>
                <feedback><report_metadata><report_id>&payload;</report_id></report_metadata></feedback>
                """;

        assertThatThrownBy(() -> parser.parse(xxe))
                .isInstanceOf(DmarcParseException.class);
    }

    @Test
    @DisplayName("refuses XML that is not a DMARC report")
    void refusesUnrelatedXml() {
        assertThatThrownBy(() -> parser.parse("<rss><channel><title>Not DMARC</title></channel></rss>"))
                .isInstanceOf(DmarcParseException.class)
                .hasMessageContaining("feedback");
    }

    @Test
    @DisplayName("refuses malformed XML")
    void refusesMalformedXml() {
        assertThatThrownBy(() -> parser.parse("<feedback><report_metadata>"))
                .isInstanceOf(DmarcParseException.class)
                .hasMessageContaining("Malformed XML");
    }

    @Test
    @DisplayName("reports which required field is missing")
    void reportsMissingRequiredField() {
        String noReportId = """
                <feedback>
                  <report_metadata><org_name>acme</org_name></report_metadata>
                  <policy_published><domain>acme.test</domain></policy_published>
                </feedback>
                """;

        assertThatThrownBy(() -> parser.parse(noReportId))
                .isInstanceOf(DmarcParseException.class)
                .hasMessageContaining("report_id");
    }

    @Test
    @DisplayName("refuses a report that carries no records")
    void refusesEmptyReport() {
        String noRecords = """
                <feedback>
                  <report_metadata><report_id>abc-1</report_id></report_metadata>
                  <policy_published><domain>acme.test</domain></policy_published>
                </feedback>
                """;

        assertThatThrownBy(() -> parser.parse(noRecords))
                .isInstanceOf(DmarcParseException.class)
                .hasMessageContaining("no <record>");
    }
}
