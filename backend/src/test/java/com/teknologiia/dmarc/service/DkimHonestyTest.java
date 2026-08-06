package com.teknologiia.dmarc.service;

import com.teknologiia.dmarc.dto.analysis.DnsRecordResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the DKIM check is allowed to claim.
 *
 * <p>DNS offers no way to list the selectors a domain publishes: a key is found by
 * asking for a name and seeing whether anything answers. Failing to find one is
 * therefore evidence about the guesses, not about the domain — and a check that
 * reported it as "no DKIM" would be stating something it cannot know.
 *
 * <p>No DNS and no Spring context, matching {@link SpfScoringTest}: the parts that
 * decide what to try and what to say are pure, and a suite that needs the internet
 * to run is one that fails for reasons that have nothing to do with the code.
 */
class DkimHonestyTest {

    // ─── What gets tried, and in what order ─────────────────────────

    @Test
    @DisplayName("a selector the caller named is tried before any guess")
    void suppliedSelectorComesFirst() {
        // It is the only name in the process that is not a guess: the caller is
        // stating what signs their mail, which is the one thing DNS cannot be asked.
        List<String> known = DomainAnalysisService.knownDkimSelectors(
                "my-own", List.of("google", "selector1"));

        assertThat(known).containsExactly("my-own", "google", "selector1");
    }

    @Test
    @DisplayName("it is normalised rather than trusted as typed")
    void suppliedSelectorIsNormalised() {
        assertThat(DomainAnalysisService.knownDkimSelectors("  Selector1  ", List.of()))
                .containsExactly("selector1");
    }

    @Test
    @DisplayName("naming one already seen in reports does not try it twice")
    void deduplicates() {
        assertThat(DomainAnalysisService.knownDkimSelectors("GOOGLE", List.of("google", "k1")))
                .containsExactly("google", "k1");
    }

    @Test
    @DisplayName("nothing supplied leaves the ones the reports actually showed")
    void fallsBackToReports() {
        assertThat(DomainAnalysisService.knownDkimSelectors(null, List.of("k1", "k2")))
                .containsExactly("k1", "k2");
        assertThat(DomainAnalysisService.knownDkimSelectors("   ", List.of("k1")))
                .containsExactly("k1");
    }

    @Test
    @DisplayName("no selector from anywhere is an empty list, not a null")
    void emptyIsEmpty() {
        assertThat(DomainAnalysisService.knownDkimSelectors(null, List.of())).isEmpty();
    }

    // ─── What it says when nothing answered ─────────────────────────

    @Test
    @DisplayName("nothing found is indeterminate, never not_found")
    void nothingFoundIsIndeterminate() {
        DnsRecordResult result = DomainAnalysisService.dkimIndeterminate(
                List.of("default", "google", "selector1"), List.of());

        assertThat(result.status())
                .as("not_found would state something DNS cannot establish")
                .isEqualTo("indeterminate");
    }

    @Test
    @DisplayName("the names tried are returned, not merely how many")
    void listsTheNamesTried() {
        List<String> candidates = List.of("default", "google", "selector1");

        DnsRecordResult result = DomainAnalysisService.dkimIndeterminate(candidates, List.of());

        // "Checked 12 selectors" invites the reader to conclude there is no key. The
        // list makes it obvious that theirs may simply not be on it, and tells them
        // what to type into the box that lets them name it.
        assertThat(result.parsed().get("selectorsTried")).isEqualTo(candidates);
        assertThat(result.parsed().get("selectorsChecked")).isEqualTo(3);
    }

    @Test
    @DisplayName("selectors seen in reports are called out separately")
    void separatesWhatCameFromReports() {
        DnsRecordResult result = DomainAnalysisService.dkimIndeterminate(
                List.of("k1", "default", "google"), List.of("k1"));

        // These are not guesses: mail was signed with them at some point, so one of
        // them failing to resolve now is worth more attention than a guess missing.
        assertThat(result.parsed().get("selectorsFromReports")).isEqualTo(List.of("k1"));
    }

    @Test
    @DisplayName("nothing from reports leaves the key out rather than showing an empty one")
    void omitsTheReportKeyWhenThereIsNothingToShow() {
        DnsRecordResult result = DomainAnalysisService.dkimIndeterminate(
                List.of("default"), List.of());

        assertThat(result.parsed()).doesNotContainKey("selectorsFromReports");
    }

    @Test
    @DisplayName("the summary states the limitation where it is actually read")
    void summaryStatesTheLimitation() {
        String summary = DomainAnalysisService
                .dkimIndeterminate(List.of("default"), List.of()).summary();

        // A status code nobody sees is not a disclosure. The sentence has to say it.
        assertThat(summary).contains("cannot be listed from DNS");
        assertThat(summary)
                .as("and it must not read as a finding of absence")
                .contains("not the same as having no DKIM");
    }

    @Test
    @DisplayName("it points at the remedy rather than leaving a dead end")
    void summaryPointsAtTheRemedy() {
        assertThat(DomainAnalysisService.dkimIndeterminate(List.of("default"), List.of()).summary())
                .containsIgnoringCase("if you know your selector");
    }
}
