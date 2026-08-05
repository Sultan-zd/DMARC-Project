package com.teknologiia.dmarc.dto.analysis;

import java.util.List;

/**
 * The scoring rules as published to the dashboard.
 *
 * @param controls  one entry per control the analyser looks at, in scoring order
 * @param grades    letter grades and the score ranges they cover
 * @param ratioNote how the controls combine into the final percentage
 * @param resolver  the DNS resolver every lookup is sent to
 */
public record ScoringModelResponse(
        List<ControlModel> controls,
        List<GradeBand> grades,
        String ratioNote,
        String resolver
) {

    /**
     * @param maxPoints the control's full allowance, or {@code null} when the
     *                  control is reported but not scored
     * @param lookup    where the record lives, for readers who want to check it
     */
    public record ControlModel(
            String name,
            Integer maxPoints,
            String purpose,
            String lookup,
            List<Outcome> outcomes
    ) {}

    /**
     * @param points what this outcome is worth: negative for a deduction, or
     *               {@code null} when it moves no points at all
     * @param label  shown in place of a figure when {@code points} is null, so an
     *               outcome that only raises a warning is never printed as a zero
     */
    public record Outcome(String condition, Integer points, String label, String meaning) {

        public static Outcome scored(String condition, int points, String meaning) {
            return new Outcome(condition, points, null, meaning);
        }

        public static Outcome unscored(String condition, String label, String meaning) {
            return new Outcome(condition, null, label, meaning);
        }
    }

    public record GradeBand(String grade, int min, int max) {}
}
