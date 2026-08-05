package com.teknologiia.dmarc.service;

import com.teknologiia.dmarc.dto.analysis.ScoringModelResponse;
import com.teknologiia.dmarc.dto.analysis.ScoringModelResponse.ControlModel;
import com.teknologiia.dmarc.dto.analysis.ScoringModelResponse.GradeBand;
import com.teknologiia.dmarc.dto.analysis.ScoringModelResponse.Outcome;

import java.util.List;

/**
 * The scoring rules, in one place.
 *
 * {@link DomainAnalysisService} reads its point values from here, and {@code
 * /api/analysis/scoring-model} publishes the same constants to the dashboard. The
 * previous arrangement — numbers inlined in the scorer, prose describing them in a
 * React component — had drifted on eight separate values: the page still credited
 * BIMI with five points it never awarded, and placed the grade boundaries a full
 * band away from where {@code computeGrade} puts them.
 *
 * Changing a number here changes the engine, the published model and the printed
 * report together. {@code ScoringModelTest} analyses crafted records and asserts
 * the engine really does award what this class advertises.
 */
public final class ScoringModel {

    private ScoringModel() {
    }

    // ─── Control allowances ─────────────────────────────────────────
    // BIMI is deliberately absent: it displays a brand logo and requires a paid
    // certificate, so it says nothing about whether the domain can be spoofed.

    public static final int MAX_DMARC = 40;
    public static final int MAX_SPF = 30;
    public static final int MAX_DKIM = 20;
    public static final int MAX_MX = 10;

    // ─── DMARC ──────────────────────────────────────────────────────

    public static final int DMARC_REJECT = MAX_DMARC;
    public static final int DMARC_QUARANTINE = 28;
    public static final int DMARC_NONE = 12;
    /** Deducted when the policy publishes no rua= address to report back to. */
    public static final int DMARC_NO_RUA_PENALTY = 6;

    // ─── SPF ────────────────────────────────────────────────────────

    public static final int SPF_HARD_FAIL = MAX_SPF;
    public static final int SPF_SOFT_FAIL = 22;
    public static final int SPF_NEUTRAL = 10;
    public static final int SPF_NO_ALL = 12;
    public static final int SPF_PASS_ALL = 0;
    /** RFC 7208 §4.6.4 stops receivers after this many DNS-resolving mechanisms. */
    public static final int SPF_LOOKUP_LIMIT = 10;
    public static final int SPF_LOOKUP_WARNING = 8;

    // ─── DKIM ───────────────────────────────────────────────────────

    public static final int DKIM_STRONG = MAX_DKIM;
    public static final int DKIM_WEAK_KEY = 12;
    public static final int DKIM_MIN_KEY_BITS = 2048;

    // ─── MX ─────────────────────────────────────────────────────────

    public static final int MX_PRESENT = MAX_MX;

    // ─── Grades ─────────────────────────────────────────────────────

    public static final int GRADE_A_PLUS = 90;
    public static final int GRADE_A = 80;
    public static final int GRADE_B = 70;
    public static final int GRADE_C = 60;
    public static final int GRADE_D = 50;

    /**
     * The published model, in the shape the dashboard renders.
     *
     * <p>Every figure below is the constant the scorer uses, never a literal typed
     * out a second time.
     */
    public static ScoringModelResponse published() {
        return new ScoringModelResponse(
                List.of(
                        new ControlModel("DMARC", MAX_DMARC,
                                "The policy that tells receivers what to do with mail that fails "
                                        + "authentication, and where to send the reports.",
                                "_dmarc.<domain> — TXT",
                                List.of(
                                        Outcome.scored("p=reject", DMARC_REJECT,
                                                "Failing mail is rejected outright."),
                                        Outcome.scored("p=quarantine", DMARC_QUARANTINE,
                                                "Failing mail is delivered to the spam folder."),
                                        Outcome.scored("p=none", DMARC_NONE,
                                                "Nothing is enforced; the policy only collects reports."),
                                        Outcome.scored("No DMARC record", 0,
                                                "Anyone can send mail as this domain and receivers will not question it."),
                                        Outcome.scored("No rua= address", -DMARC_NO_RUA_PENALTY,
                                                "Deducted from the above: without a reporting address the owner "
                                                        + "never learns who is sending as their domain."))),

                        new ControlModel("SPF", MAX_SPF,
                                "The list of servers allowed to send mail for the domain, and how "
                                        + "strictly receivers should treat everyone else.",
                                "<domain> — TXT starting v=spf1",
                                List.of(
                                        Outcome.scored("-all (hard fail)", SPF_HARD_FAIL,
                                                "Unlisted servers are rejected."),
                                        Outcome.scored("~all (soft fail)", SPF_SOFT_FAIL,
                                                "Unlisted servers are accepted but marked."),
                                        Outcome.scored("?all (neutral)", SPF_NEUTRAL,
                                                "Receivers are told to take no action at all."),
                                        Outcome.scored("No all mechanism", SPF_NO_ALL,
                                                "Unlisted senders fall through as neutral."),
                                        Outcome.scored("+all", SPF_PASS_ALL,
                                                "Authorizes every server on the internet to send as this domain."),
                                        Outcome.unscored("Over " + SPF_LOOKUP_LIMIT + " DNS lookups", "flagged",
                                                "No points are moved. Receivers stop evaluating past the RFC 7208 "
                                                        + "limit, so SPF starts failing for legitimate mail — worth "
                                                        + "fixing even though the score does not show it."))),

                        new ControlModel("DKIM", MAX_DKIM,
                                "The cryptographic signature that survives forwarding, which SPF "
                                        + "does not.",
                                "<selector>._domainkey.<domain> — TXT",
                                List.of(
                                        Outcome.scored("Key of " + DKIM_MIN_KEY_BITS + " bits or more", DKIM_STRONG,
                                                "A signature receivers will trust."),
                                        Outcome.scored("Key under " + DKIM_MIN_KEY_BITS + " bits", DKIM_WEAK_KEY,
                                                "Published but weak enough to be forged with effort."),
                                        Outcome.scored("No key found", 0,
                                                "DMARC has to rely on SPF alone, which breaks when mail is forwarded."),
                                        Outcome.unscored("Selector unknown", "excluded",
                                                "DKIM keys are only readable if you know the selector. When none of "
                                                        + "the tried selectors answer, the control is dropped from both "
                                                        + "sides of the ratio rather than scored as a failure."))),

                        new ControlModel("MX", MAX_MX,
                                "Whether the domain is set up to receive mail at all.",
                                "<domain> — MX",
                                List.of(
                                        Outcome.scored("Mail servers published", MX_PRESENT,
                                                "The domain can receive mail, including bounces and replies."),
                                        Outcome.scored("No MX record", 0,
                                                "The domain cannot receive mail."))),

                        new ControlModel("BIMI", null,
                                "Shows the brand's logo beside its messages in supporting inboxes. "
                                        + "Reported for completeness but never scored: it needs a paid "
                                        + "certificate and says nothing about whether the domain can be spoofed.",
                                "default._bimi.<domain> — TXT",
                                List.of())),

                List.of(
                        new GradeBand("A+", GRADE_A_PLUS, 100),
                        new GradeBand("A", GRADE_A, GRADE_A_PLUS - 1),
                        new GradeBand("B", GRADE_B, GRADE_A - 1),
                        new GradeBand("C", GRADE_C, GRADE_B - 1),
                        new GradeBand("D", GRADE_D, GRADE_C - 1),
                        new GradeBand("F", 0, GRADE_D - 1)),

                "The score is the share of applicable points earned, not a running total. "
                        + "A control that cannot be determined — DKIM when no selector answers — "
                        + "is removed from the numerator and the denominator alike, so an "
                        + "unreadable key never reads as a missing one.",

                "8.8.8.8");
    }
}
