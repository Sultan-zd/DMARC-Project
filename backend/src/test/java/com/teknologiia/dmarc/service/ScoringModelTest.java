package com.teknologiia.dmarc.service;

import com.teknologiia.dmarc.dto.analysis.DnsRecordResult;
import com.teknologiia.dmarc.dto.analysis.RecommendationDTO;
import com.teknologiia.dmarc.dto.analysis.ScoringModelResponse;
import com.teknologiia.dmarc.dto.analysis.ScoringModelResponse.ControlModel;
import com.teknologiia.dmarc.dto.analysis.ScoringModelResponse.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holds the published scoring model to what the engine actually awards.
 *
 * <p>The dashboard used to describe the scale in hand-written prose. It fell eight
 * values behind — crediting BIMI with points it never awarded, and drawing the
 * grade boundaries a whole band away from {@code computeGrade}. These tests fail
 * the moment the two disagree again.
 */
class ScoringModelTest {

    // Only the pure scoring helpers are exercised, so no collaborator is needed.
    // scoreSpf resolves DNS only for records carrying redirect=; none here do.
    private final DomainAnalysisService service =
            new DomainAnalysisService(null, null, null, null, null);

    private final ScoringModelResponse model = ScoringModel.published();

    private List<RecommendationDTO> recs() {
        return new ArrayList<>();
    }

    private DnsRecordResult dmarc(String policy, boolean withRua) {
        Map<String, Object> parsed = withRua
                ? Map.of("v", "DMARC1", "p", policy, "rua", "mailto:d@example.com")
                : Map.of("v", "DMARC1", "p", policy);
        return new DnsRecordResult("DMARC", "found", "v=DMARC1; p=" + policy, parsed, "");
    }

    private DnsRecordResult spf(String record) {
        return new DnsRecordResult("SPF", "found", record, Map.of(), "");
    }

    private DnsRecordResult missing(String type) {
        return new DnsRecordResult(type, "not_found", null, Map.of(), "");
    }

    /** The points the published model attaches to a named outcome of a control. */
    private int published(String control, String condition) {
        ControlModel c = model.controls().stream()
                .filter(x -> x.name().equals(control)).findFirst()
                .orElseThrow(() -> new AssertionError("control absent du modele : " + control));
        Outcome o = c.outcomes().stream()
                .filter(x -> x.condition().equals(condition)).findFirst()
                .orElseThrow(() -> new AssertionError("issue absente du modele : " + condition));
        assertThat(o.points()).as("issue chiffree : " + condition).isNotNull();
        return o.points();
    }

    // ─── DMARC ──────────────────────────────────────────────────────

    @Test
    @DisplayName("DMARC policies award exactly what the model publishes")
    void dmarcMatchesModel() {
        assertThat(service.scoreDmarc(dmarc("reject", true), recs()))
                .isEqualTo(published("DMARC", "p=reject"));
        assertThat(service.scoreDmarc(dmarc("quarantine", true), recs()))
                .isEqualTo(published("DMARC", "p=quarantine"));
        assertThat(service.scoreDmarc(dmarc("none", true), recs()))
                .isEqualTo(published("DMARC", "p=none"));
        assertThat(service.scoreDmarc(missing("DMARC"), recs()))
                .isEqualTo(published("DMARC", "No DMARC record"));
    }

    @Test
    @DisplayName("a policy with no rua costs the published deduction")
    void missingRuaCostsThePublishedDeduction() {
        int withRua = service.scoreDmarc(dmarc("reject", true), recs());
        int withoutRua = service.scoreDmarc(dmarc("reject", false), recs());

        // The model states the deduction as a negative number.
        assertThat(withoutRua - withRua).isEqualTo(published("DMARC", "No rua= address"));
    }

    // ─── SPF ────────────────────────────────────────────────────────

    @Test
    @DisplayName("SPF qualifiers award exactly what the model publishes")
    void spfMatchesModel() {
        assertThat(service.scoreSpf(spf("v=spf1 ip4:192.0.2.1 -all"), recs()))
                .isEqualTo(published("SPF", "-all (hard fail)"));
        assertThat(service.scoreSpf(spf("v=spf1 ip4:192.0.2.1 ~all"), recs()))
                .isEqualTo(published("SPF", "~all (soft fail)"));
        assertThat(service.scoreSpf(spf("v=spf1 ip4:192.0.2.1 ?all"), recs()))
                .isEqualTo(published("SPF", "?all (neutral)"));
        assertThat(service.scoreSpf(spf("v=spf1 ip4:192.0.2.1 +all"), recs()))
                .isEqualTo(published("SPF", "+all"));
        assertThat(service.scoreSpf(spf("v=spf1 ip4:192.0.2.1"), recs()))
                .isEqualTo(published("SPF", "No all mechanism"));
        assertThat(service.scoreSpf(missing("SPF"), recs())).isZero();
    }

    @Test
    @DisplayName("exceeding the lookup budget is flagged without changing the points")
    void lookupBudgetIsFlaggedNotDeducted() {
        StringBuilder over = new StringBuilder("v=spf1");
        for (int i = 0; i <= ScoringModel.SPF_LOOKUP_LIMIT; i++) {
            over.append(" include:h").append(i).append(".example.com");
        }
        over.append(" -all");

        List<RecommendationDTO> found = recs();
        int score = service.scoreSpf(spf(over.toString()), found);

        assertThat(score).isEqualTo(published("SPF", "-all (hard fail)"));
        assertThat(found).anyMatch(r -> "critical".equals(r.severity())
                && r.message().contains("DNS lookups"));
    }

    // ─── DKIM ───────────────────────────────────────────────────────

    @Test
    @DisplayName("DKIM key strength awards exactly what the model publishes")
    void dkimMatchesModel() {
        DnsRecordResult strong = new DnsRecordResult("DKIM", "found", "v=DKIM1; p=…",
                Map.of("keySizeBits", ScoringModel.DKIM_MIN_KEY_BITS), "");
        DnsRecordResult weak = new DnsRecordResult("DKIM", "found", "v=DKIM1; p=…",
                Map.of("keySizeBits", 1024), "");

        assertThat(service.scoreDkim(strong, recs()))
                .isEqualTo(published("DKIM", "Key of " + ScoringModel.DKIM_MIN_KEY_BITS + " bits or more"));
        assertThat(service.scoreDkim(weak, recs()))
                .isEqualTo(published("DKIM", "Key under " + ScoringModel.DKIM_MIN_KEY_BITS + " bits"));
        assertThat(service.scoreDkim(missing("DKIM"), recs()))
                .isEqualTo(published("DKIM", "No key found"));
    }

    @Test
    @DisplayName("an unreadable selector is published as unscored, not as zero")
    void indeterminateDkimIsPublishedAsUnscored() {
        // The caller drops the control from both sides of the ratio; the model has to
        // say so rather than imply the domain lost twenty points.
        ControlModel dkim = model.controls().stream()
                .filter(c -> c.name().equals("DKIM")).findFirst().orElseThrow();
        Outcome unknown = dkim.outcomes().stream()
                .filter(o -> o.condition().equals("Selector unknown")).findFirst().orElseThrow();

        assertThat(unknown.points()).isNull();
    }

    // ─── MX ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("MX awards exactly what the model publishes")
    void mxMatchesModel() {
        DnsRecordResult present = new DnsRecordResult("MX", "found", "10 mail.example.com",
                Map.of(), "");

        assertThat(service.scoreMx(present, recs()))
                .isEqualTo(published("MX", "Mail servers published"));
        assertThat(service.scoreMx(missing("MX"), recs()))
                .isEqualTo(published("MX", "No MX record"));
    }

    // ─── Structure ──────────────────────────────────────────────────

    @Test
    @DisplayName("BIMI is published as carrying no points")
    void bimiIsUnscored() {
        ControlModel bimi = model.controls().stream()
                .filter(c -> c.name().equals("BIMI")).findFirst().orElseThrow();

        // The page used to advertise a +5 bonus for BIMI. Nothing in the engine has
        // ever awarded it.
        assertThat(bimi.maxPoints()).isNull();
    }

    @Test
    @DisplayName("the scored allowances add up to 100")
    void scoredAllowancesAddUpToOneHundred() {
        int total = model.controls().stream()
                .map(ControlModel::maxPoints)
                .filter(java.util.Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();

        assertThat(total).isEqualTo(100);
    }

    @Test
    @DisplayName("the published grade bands are the ones computeGrade applies")
    void gradeBandsMatchTheEngine() {
        for (ScoringModelResponse.GradeBand band : model.grades()) {
            assertThat(service.computeGrade(band.min()))
                    .as("bas de la bande " + band.grade()).isEqualTo(band.grade());
            assertThat(service.computeGrade(band.max()))
                    .as("haut de la bande " + band.grade()).isEqualTo(band.grade());
        }
    }

    @Test
    @DisplayName("the grade bands leave no score uncovered")
    void gradeBandsCoverEveryScore() {
        for (int score = 0; score <= 100; score++) {
            int s = score;
            assertThat(model.grades())
                    .as("score " + s)
                    .anyMatch(b -> s >= b.min() && s <= b.max());
        }
    }
}
