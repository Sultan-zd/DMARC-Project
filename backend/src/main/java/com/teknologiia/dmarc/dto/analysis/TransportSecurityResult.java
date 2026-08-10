package com.teknologiia.dmarc.dto.analysis;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Whether mail to this domain travels encrypted, and whether the domain insists.
 *
 * <p>Kept apart from the {@code /100} grade rather than folded into it. Adding a
 * fifth control to a score built from four would take points away from the others,
 * and a domain that changed nothing would drop a grade overnight — the number would
 * stop meaning what it meant last week. This carries its own grade, shown beside
 * the other one.
 *
 * @param declared what the domain publishes about TLS: MTA-STS, TLS-RPT, DANE.
 *                 All DNS and HTTPS, so it answers everywhere
 * @param hosts    what each MX server actually offered when connected to. Requires
 *                 outbound port 25, which most cloud providers block — when that is
 *                 the case the list says so rather than reporting no TLS
 */
public record TransportSecurityResult(
        String domain,
        String grade,
        int score,
        Declared declared,
        List<HostReport> hosts,
        boolean probeAttempted,
        String probeUnavailableReason,
        List<String> findings,
        LocalDateTime checkedAt
) {

    /**
     * What the domain says about TLS in DNS, independently of any server.
     *
     * @param mtaStsMode {@code enforce}, {@code testing}, {@code none}, or null when
     *                   no policy is published. Enforce is the strongest statement a
     *                   domain can make: senders must use TLS with a valid
     *                   certificate or not deliver at all
     */
    public record Declared(
            boolean mtaStsRecord,
            boolean mtaStsPolicy,
            String mtaStsMode,
            String mtaStsId,
            List<String> mtaStsMx,
            Long mtaStsMaxAgeDays,
            boolean tlsRpt,
            String tlsRptAddresses,
            String policyError
    ) {}

    /**
     * One MX host, as it answered.
     *
     * @param protocols  every version tried and whether it was accepted. TLS 1.0 and
     *                   1.1 are deprecated (RFC 8996) and their presence is the
     *                   finding, not their absence
     * @param daysToExpiry negative once the certificate has already expired
     */
    public record HostReport(
            String host,
            int priority,
            boolean reachable,
            String unreachableReason,
            boolean startTlsOffered,
            String certificateSubject,
            String certificateIssuer,
            LocalDateTime certificateNotBefore,
            LocalDateTime certificateNotAfter,
            Long daysToExpiry,
            boolean nameMatches,
            String keyAlgorithm,
            Integer keyBits,
            String signatureAlgorithm,
            List<ProtocolSupport> protocols,
            boolean daneTlsa,
            List<String> notes
    ) {}

    public record ProtocolSupport(String version, boolean accepted, boolean deprecated) {}
}
