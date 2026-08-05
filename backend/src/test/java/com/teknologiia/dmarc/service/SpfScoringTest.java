package com.teknologiia.dmarc.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SPF rules that were previously wrong, pinned down.
 *
 * <p>These exercise pure parsing helpers, so they need neither DNS nor a Spring
 * context.
 */
class SpfScoringTest {

    // Only the pure parsing helpers are exercised here, so no collaborator is needed.
    private final DomainAnalysisService service =
            new DomainAnalysisService(null, null, null, null, null);

    // ─── DNS lookup budget (RFC 7208 §4.6.4) ────────────────────────

    @ParameterizedTest
    @CsvSource({
            // Only these mechanisms resolve DNS.
            "'v=spf1 include:a.com include:b.com -all',                    2",
            "'v=spf1 a mx -all',                                           2",
            "'v=spf1 a:mail.example.com mx:mx.example.com -all',           2",
            "'v=spf1 exists:%{i}._spf.example.com -all',                   1",
            "'v=spf1 redirect=_spf.example.com',                           1",
            "'v=spf1 ptr -all',                                            1",
            // ip4 and ip6 resolve nothing at all.
            "'v=spf1 ip4:1.2.3.4 ip4:5.6.7.8 ip6:2001:db8::1 -all',        0",
            "'v=spf1 -all',                                                0"
    })
    @DisplayName("counts only the mechanisms that actually resolve DNS")
    void countsResolvingMechanismsOnly(String record, int expected) {
        assertThat(service.countSpfDnsLookups(record)).isEqualTo(expected);
    }

    @Test
    @DisplayName("an IP-heavy record costs no lookups, however long it is")
    void ipHeavyRecordCostsNothing() {
        // The previous check counted spaces, so a record listing many IP ranges — a
        // perfectly efficient one — was reported as exceeding the 10-lookup limit.
        StringBuilder record = new StringBuilder("v=spf1");
        for (int i = 0; i < 30; i++) {
            record.append(" ip4:192.0.2.").append(i);
        }
        record.append(" -all");

        assertThat(service.countSpfDnsLookups(record.toString())).isZero();
    }

    @Test
    @DisplayName("a record over the limit is counted as such")
    void detectsRecordsOverTheLimit() {
        StringBuilder record = new StringBuilder("v=spf1");
        for (int i = 0; i < 12; i++) {
            record.append(" include:host").append(i).append(".example.com");
        }
        record.append(" -all");

        assertThat(service.countSpfDnsLookups(record.toString())).isEqualTo(12);
    }

    @Test
    @DisplayName("qualifiers do not hide a mechanism from the count")
    void qualifiersDoNotHideMechanisms() {
        assertThat(service.countSpfDnsLookups("v=spf1 +include:a.com ~mx -all")).isEqualTo(2);
    }

    // ─── redirect= ──────────────────────────────────────────────────

    @Test
    @DisplayName("finds the target of a redirect modifier")
    void findsRedirectTarget() {
        // facebook.com publishes exactly this. Without following it, the record looks
        // like it has no 'all' mechanism and was scored as if unprotected.
        assertThat(service.redirectTarget("v=spf1 redirect=_spf.facebook.com"))
                .isEqualTo("_spf.facebook.com");
    }

    @Test
    @DisplayName("reports no target when the record does not redirect")
    void noRedirectTarget() {
        assertThat(service.redirectTarget("v=spf1 include:_spf.google.com -all")).isNull();
    }
}
