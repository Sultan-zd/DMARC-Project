package com.teknologiia.dmarc.dto.stats;

import java.time.LocalDateTime;

/**
 * Current email-security posture of one domain, from its latest analysis.
 *
 * <p>This is configuration, not traffic. It answers "can this domain be spoofed?",
 * where the report-derived figures answer "what was actually sent?". Both matter,
 * and neither substitutes for the other.
 */
public record DomainPosture(
        String domain,
        Integer score,
        String grade,

        /** none | quarantine | reject | null when no DMARC record exists. */
        String policy,

        /** Percentage of mail the policy is applied to (DMARC `pct`). */
        Integer pct,

        /** Subdomain policy, which is what an attacker targets when it is weaker. */
        String subdomainPolicy,

        /** Whether an aggregate reporting address is published — no rua, no visibility. */
        Boolean reportingConfigured,

        String spfStatus,
        String dkimStatus,

        LocalDateTime analyzedAt,

        /** Score at the previous analysis, or null when this is the first. */
        Integer previousScore,

        /** Reports stored for this domain, so the UI can say whether traffic data exists. */
        Long reportCount
) {
    /** Positive when posture improved since the previous analysis. */
    public Integer scoreDelta() {
        return previousScore == null || score == null ? null : score - previousScore;
    }
}
