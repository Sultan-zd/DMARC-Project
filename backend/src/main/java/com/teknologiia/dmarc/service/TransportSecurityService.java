package com.teknologiia.dmarc.service;

import com.teknologiia.dmarc.dto.analysis.TransportSecurityResult;
import com.teknologiia.dmarc.dto.analysis.TransportSecurityResult.Declared;
import com.teknologiia.dmarc.dto.analysis.TransportSecurityResult.HostReport;
import com.teknologiia.dmarc.dto.analysis.TransportSecurityResult.ProtocolSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.MXRecord;
import org.xbill.DNS.Record;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.Type;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.*;

/**
 * Whether mail to a domain travels encrypted.
 *
 * <p>The natural next control after DMARC, SPF and DKIM: those say who may send as
 * a domain, this says whether what is sent to it can be read on the way.
 *
 * <p>Two halves, deliberately separated because they do not have the same
 * availability.
 *
 * <p><strong>What the domain declares</strong> — MTA-STS, TLS-RPT, DANE — is DNS and
 * HTTPS, so it answers from anywhere. It is also the stronger evidence: a domain in
 * MTA-STS {@code enforce} mode is telling every sender in the world to refuse
 * delivery rather than send in the clear, which no certificate being valid today
 * can match.
 *
 * <p><strong>What the servers offer</strong> — the certificate and the protocol
 * versions — needs an outbound connection on port 25, and most hosting providers
 * block that to stop their machines being used for spam. When that is the case the
 * result says so plainly rather than reporting an absence of TLS, for the same
 * reason the DKIM check reports "could not determine" instead of "no key".
 *
 * <p>Graded on its own scale, shown beside the {@code /100} rather than inside it.
 * Folding a fifth control into a score built from four would take points from the
 * others, and a domain that changed nothing would drop a grade overnight.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TransportSecurityService {

    /** Enough to characterise a domain; a few large providers list a dozen. */
    private static final int MAX_HOSTS = 4;

    private final MtaStsClient mtaSts;
    private final TlsProbe probe;

    @Value("${app.transport.probe-enabled:true}")
    private boolean probeEnabled;

    /** A stuck host must not hold the whole check; hosts are probed in parallel. */
    @Value("${app.transport.total-timeout-seconds:45}")
    private long totalTimeoutSeconds;

    public TransportSecurityResult check(String domain) {
        String name = domain.trim().toLowerCase(Locale.ROOT);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        Declared declared = mtaSts.check(name);
        List<MXRecord> mxRecords = mxOf(name);

        List<HostReport> hosts = new ArrayList<>();
        boolean probeAttempted = false;
        String probeUnavailable = null;

        if (!probeEnabled) {
            probeUnavailable = "Live probing is switched off on this deployment "
                    + "(app.transport.probe-enabled).";
        } else if (mxRecords.isEmpty()) {
            probeUnavailable = "No MX records, so there is no mail server to connect to.";
        } else {
            probeAttempted = true;
            hosts = probeAll(mxRecords);

            // Every host unreachable, with none of them refusing, is the signature of
            // a blocked port rather than of four broken mail servers. Saying which is
            // the difference between a useful result and a misleading one.
            boolean noneReachable = hosts.stream().noneMatch(HostReport::reachable);
            if (noneReachable) {
                probeUnavailable = "No mail server could be reached on port 25. Outbound "
                        + "port 25 is blocked by most hosting providers, so this is far "
                        + "more likely to be a limit of where this check runs from than "
                        + "a fault with the domain. What the domain declares, above, is "
                        + "unaffected.";
            }
        }

        List<String> findings = new ArrayList<>();
        int score = grade(declared, hosts, probeAttempted && probeUnavailable == null, findings);

        return new TransportSecurityResult(name, letter(score), score, declared, hosts,
                probeAttempted, probeUnavailable, findings, now);
    }

    // ─── Probing ────────────────────────────────────────────────────

    private List<HostReport> probeAll(List<MXRecord> mxRecords) {
        List<MXRecord> targets = mxRecords.stream()
                .sorted(Comparator.comparingInt(MXRecord::getPriority))
                .limit(MAX_HOSTS)
                .toList();

        // A small pool sized to the work: four hosts probed one after another would
        // take four times as long for no reason, and each one is mostly waiting.
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, targets.size()));
        try {
            List<Future<HostReport>> pending = targets.stream()
                    .map(mx -> pool.submit(() -> probeHost(
                            stripTrailingDot(mx.getTarget().toString(true)), mx.getPriority())))
                    .toList();

            List<HostReport> reports = new ArrayList<>();
            for (Future<HostReport> future : pending) {
                try {
                    reports.add(future.get(totalTimeoutSeconds, TimeUnit.SECONDS));
                } catch (Exception e) {
                    log.debug("Host probe did not finish: {}", e.getMessage());
                }
            }
            return reports;
        } finally {
            pool.shutdownNow();
        }
    }

    private HostReport probeHost(String host, int priority) {
        List<String> notes = new ArrayList<>();
        boolean dane = hasTlsa(host);

        TlsProbe.Certificate certificate = probe.certificateOf(host);
        if (certificate == null) {
            return new HostReport(host, priority, false,
                    "Could not complete a STARTTLS handshake on port 25.",
                    false, null, null, null, null, null, false, null, null, null,
                    List.of(), dane, notes);
        }

        List<ProtocolSupport> protocols = new ArrayList<>();
        for (TlsProbe.Version version : TlsProbe.VERSIONS) {
            protocols.add(new ProtocolSupport(version.label(),
                    probe.accepts(host, version), version.deprecated()));
        }

        LocalDateTime notAfter = toLocal(certificate.notAfter());
        LocalDateTime notBefore = toLocal(certificate.notBefore());
        long daysLeft = ChronoUnit.DAYS.between(LocalDateTime.now(ZoneOffset.UTC), notAfter);

        boolean nameMatches = matches(host, certificate);
        if (!nameMatches) {
            notes.add("The certificate does not name this host. A sender enforcing "
                    + "MTA-STS would refuse to deliver here.");
        }
        if (certificate.keyBits() != null && "RSA".equals(certificate.keyAlgorithm())
                && certificate.keyBits() < 2048) {
            notes.add("The key is " + certificate.keyBits() + " bits; 2048 is the minimum "
                    + "considered sound.");
        }
        if (certificate.signatureAlgorithm() != null
                && certificate.signatureAlgorithm().toUpperCase(Locale.ROOT).contains("SHA1")) {
            notes.add("Signed with SHA-1, which is no longer trusted by browsers or "
                    + "modern mail servers.");
        }

        return new HostReport(host, priority, true, null, true,
                certificate.subject(), certificate.issuer(), notBefore, notAfter, daysLeft,
                nameMatches, certificate.keyAlgorithm(), certificate.keyBits(),
                certificate.signatureAlgorithm(), protocols, dane, notes);
    }

    /** A certificate names a host if its subject or any SAN entry covers it. */
    static boolean matches(String host, TlsProbe.Certificate certificate) {
        String target = host.toLowerCase(Locale.ROOT);
        for (String name : certificate.names()) {
            if (nameCovers(name, target)) {
                return true;
            }
        }
        String subject = certificate.subject() == null ? "" : certificate.subject();
        for (String part : subject.split(",")) {
            String trimmed = part.trim();
            if (trimmed.regionMatches(true, 0, "CN=", 0, 3)
                    && nameCovers(trimmed.substring(3).toLowerCase(Locale.ROOT), target)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code *.example.com} covers {@code mx.example.com} but not
     * {@code a.mx.example.com} — a wildcard spans one label, not any number.
     */
    private static boolean nameCovers(String name, String host) {
        if (name.equals(host)) {
            return true;
        }
        if (name.startsWith("*.")) {
            String suffix = name.substring(1);
            return host.endsWith(suffix)
                    && host.length() > suffix.length()
                    && !host.substring(0, host.length() - suffix.length()).contains(".");
        }
        return false;
    }

    // ─── Grading ────────────────────────────────────────────────────

    /**
     * A score out of 100 for transport, on its own scale.
     *
     * <p>Weighted towards what the domain declares rather than what a server happens
     * to offer today, because a declaration binds every sender while a certificate
     * only describes one moment. And because the declared half can always be
     * measured, whereas the probe cannot.
     */
    private int grade(Declared declared, List<HostReport> hosts, boolean probed,
                      List<String> findings) {
        int score = 0;

        // ── What the domain declares: 60 ──
        if (declared.mtaStsRecord() && declared.mtaStsPolicy()) {
            String mode = declared.mtaStsMode() == null ? "" : declared.mtaStsMode();
            switch (mode.toLowerCase(Locale.ROOT)) {
                case "enforce" -> score += 45;
                case "testing" -> {
                    score += 25;
                    findings.add("MTA-STS is in testing mode: failures are reported but "
                            + "mail is still delivered in the clear. Moving to enforce is "
                            + "what makes the policy binding.");
                }
                default -> {
                    score += 10;
                    findings.add("An MTA-STS policy exists but its mode is `" + mode
                            + "`, which asks senders for nothing.");
                }
            }
        } else if (declared.mtaStsRecord()) {
            score += 5;
            findings.add("An MTA-STS record is published but the policy could not be "
                    + "fetched" + (declared.policyError() == null ? ""
                    : " (" + declared.policyError() + ")")
                    + ". Senders fall back to opportunistic TLS, so the domain is not "
                    + "protected while appearing to be.");
        } else {
            findings.add("No MTA-STS policy. Senders use opportunistic TLS, which an "
                    + "attacker in the path can strip by removing STARTTLS from the "
                    + "server's reply — the message then goes in the clear.");
        }

        if (declared.tlsRpt()) {
            score += 15;
        } else {
            findings.add("No TLS-RPT record. Delivery failures caused by TLS problems "
                    + "go unreported, so a broken certificate is discovered by somebody "
                    + "complaining rather than by a report.");
        }

        // ── What the servers offer: 40, only when they could be reached ──
        if (!probed || hosts.isEmpty()) {
            // Scaled up rather than left at 60, so an unreachable probe does not read
            // as a failing grade. The result says separately that it could not run.
            return Math.min(100, Math.round(score * 100f / 60f));
        }

        List<HostReport> reachable = hosts.stream().filter(HostReport::reachable).toList();
        if (reachable.isEmpty()) {
            return Math.min(100, Math.round(score * 100f / 60f));
        }

        boolean anyExpired = reachable.stream()
                .anyMatch(h -> h.daysToExpiry() != null && h.daysToExpiry() < 0);
        boolean anyExpiringSoon = reachable.stream()
                .anyMatch(h -> h.daysToExpiry() != null
                        && h.daysToExpiry() >= 0 && h.daysToExpiry() <= 14);
        boolean anyDeprecated = reachable.stream().anyMatch(h -> h.protocols().stream()
                .anyMatch(p -> p.deprecated() && p.accepted()));
        boolean allModern = reachable.stream().allMatch(h -> h.protocols().stream()
                .anyMatch(p -> "TLS 1.2".equals(p.version()) && p.accepted()
                        || "TLS 1.3".equals(p.version()) && p.accepted()));
        boolean anyNameMismatch = reachable.stream().anyMatch(h -> !h.nameMatches());

        if (anyExpired) {
            findings.add("A certificate has already expired. A sender enforcing MTA-STS "
                    + "will refuse to deliver, and mail to this domain is bouncing now.");
        } else if (anyExpiringSoon) {
            long days = reachable.stream()
                    .filter(h -> h.daysToExpiry() != null)
                    .mapToLong(HostReport::daysToExpiry).min().orElse(0);
            findings.add("A certificate expires in " + days + " day" + (days == 1 ? "" : "s")
                    + ". Renewing it is not urgent yet, and will be.");
            score += 10;
        } else {
            score += 15;
        }

        if (anyDeprecated) {
            String versions = reachable.stream()
                    .flatMap(h -> h.protocols().stream())
                    .filter(p -> p.deprecated() && p.accepted())
                    .map(ProtocolSupport::version)
                    .distinct().sorted().reduce((a, b) -> a + " and " + b).orElse("");
            findings.add("A mail server still accepts " + versions + ", deprecated by "
                    + "RFC 8996. Both have known weaknesses and no reason to remain "
                    + "enabled — every current sender speaks 1.2 or 1.3.");
        } else {
            score += 15;
        }

        if (allModern) {
            score += 5;
        } else {
            findings.add("A mail server does not accept TLS 1.2 or 1.3, which modern "
                    + "senders require.");
        }

        if (anyNameMismatch) {
            findings.add("A certificate does not name the host it was served from.");
        } else {
            score += 5;
        }

        return Math.min(score, 100);
    }

    private static String letter(int score) {
        if (score >= 90) return "A+";
        if (score >= 80) return "A";
        if (score >= 70) return "B";
        if (score >= 60) return "C";
        if (score >= 50) return "D";
        return "F";
    }

    // ─── DNS ────────────────────────────────────────────────────────

    private List<MXRecord> mxOf(String domain) {
        try {
            Lookup lookup = new Lookup(domain, Type.MX);
            lookup.setResolver(new SimpleResolver("8.8.8.8"));
            lookup.setCache(null);
            Record[] results = lookup.run();
            if (results == null) {
                return List.of();
            }
            List<MXRecord> records = new ArrayList<>();
            for (Record record : results) {
                if (record instanceof MXRecord mx) {
                    records.add(mx);
                }
            }
            return records;
        } catch (Exception e) {
            log.debug("MX lookup failed for {}: {}", domain, e.getMessage());
            return List.of();
        }
    }

    /**
     * A TLSA record pins this host's certificate through DNSSEC.
     *
     * <p>Stronger than MTA-STS where it is deployed, because it does not depend on a
     * web server being reachable — but it needs the whole zone signed, which is why
     * it stays rare outside Europe.
     */
    private boolean hasTlsa(String host) {
        try {
            Lookup lookup = new Lookup("_25._tcp." + host, Type.TLSA);
            lookup.setResolver(new SimpleResolver("8.8.8.8"));
            lookup.setCache(null);
            Record[] results = lookup.run();
            return results != null && results.length > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String stripTrailingDot(String host) {
        return host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
    }

    private static LocalDateTime toLocal(java.util.Date date) {
        return date == null ? null
                : LocalDateTime.ofInstant(date.toInstant(), ZoneId.of("UTC"));
    }
}
