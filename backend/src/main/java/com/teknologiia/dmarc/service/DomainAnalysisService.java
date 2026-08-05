package com.teknologiia.dmarc.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.teknologiia.dmarc.dto.analysis.*;
import com.teknologiia.dmarc.dto.report.PaginatedResponse;
import com.teknologiia.dmarc.dto.stats.DomainPosture;
import com.teknologiia.dmarc.model.Alert;
import com.teknologiia.dmarc.model.DomainAnalysis;
import com.teknologiia.dmarc.model.Organization;
import com.teknologiia.dmarc.repository.AlertRepository;
import com.teknologiia.dmarc.repository.DmarcRecordRepository;
import com.teknologiia.dmarc.repository.DmarcReportRepository;
import com.teknologiia.dmarc.repository.DomainAnalysisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.xbill.DNS.*;
import org.xbill.DNS.Record;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class DomainAnalysisService {

    private final DomainAnalysisRepository domainAnalysisRepository;
    private final AlertRepository alertRepository;
    private final ObjectMapper objectMapper;
    private final DmarcRecordRepository recordRepository;
    private final DmarcReportRepository reportRepository;

    // Every point value used below comes from ScoringModel, which is also what
    // /api/analysis/scoring-model publishes. Numbers typed here a second time are
    // how the dashboard came to advertise a scale the engine had stopped using.

    private static final Pattern SPF_REDIRECT =
            Pattern.compile("redirect=([a-z0-9._-]+)", Pattern.CASE_INSENSITIVE);

    // DKIM selectors commonly used by popular email providers. Tried after any
    // selector observed in the organization's own reports.
    private static final List<String> DKIM_SELECTORS = List.of(
            // General & Common
            "default", "google", "selector1", "selector2", 
            // Mailchimp, SendGrid, Salesforce
            "k1", "k2", "k3", "s1", "s2", "m1", "m2", "sg", "sig1",
            // Protonmail, Zoho, Fastmail
            "protonmail", "protonmail2", "protonmail3", "zmail", "fm1", "fm2", "fm3",
            // Legacy / Standard sizes
            "dkim", "mail", "s1024", "s2048", "pm", "mandrill",
            // Amazon SES, Postmark, Mailgun, Zendesk, HubSpot, Klaviyo
            "amazonses", "pm-bounces", "mailo", "mte1", "mte2", "zendesk1", "zendesk2",
            "hs1-", "hs2-", "kl1", "kl2", "smtp", "email", "mailjet", "sparkpost"
    );

    /**
     * Perform a real DNS-based security analysis of the given domain.
     */
    public DomainAnalysisResponse analyzeDomain(String domain, String username,
                                                Organization organization) {
        return analyzeDomain(domain, username, organization, true);
    }

    /**
     * @param raiseAlerts whether findings should be pushed into the team alert feed.
     *                    Anonymous public scans pass {@code false}: a visitor checking
     *                    an unrelated domain should not generate alerts for the team.
     */
    public DomainAnalysisResponse analyzeDomain(String domain, String username,
                                                Organization organization, boolean raiseAlerts) {
        log.info("Starting DNS analysis for domain: {}", domain);

        List<DnsRecordResult> records = new ArrayList<>();
        List<RecommendationDTO> recommendations = new ArrayList<>();
        Map<String, Integer> breakdown = new LinkedHashMap<>();

        // Selectors this organization has actually observed for the domain, taken from
        // its own aggregate reports. Empty for anonymous scans, which have no tenant.
        List<String> knownDkimSelectors = organization == null
                ? List.of()
                : recordRepository.findKnownDkimSelectors(organization.getId(), domain);
        if (!knownDkimSelectors.isEmpty()) {
            log.debug("Trying {} DKIM selector(s) observed in stored reports for {}",
                    knownDkimSelectors.size(), domain);
        }

        // 1. Analyse DMARC
        DnsRecordResult dmarcResult = lookupDmarc(domain);
        records.add(dmarcResult);
        int dmarcScore = scoreDmarc(dmarcResult, recommendations);
        breakdown.put("DMARC", dmarcScore);

        // 2. Analyse SPF
        DnsRecordResult spfResult = lookupSpf(domain);
        records.add(spfResult);
        int spfScore = scoreSpf(spfResult, recommendations);
        breakdown.put("SPF", spfScore);

        // 3. Analyse DKIM
        DnsRecordResult dkimResult = lookupDkim(domain, knownDkimSelectors);
        records.add(dkimResult);
        int dkimScore = scoreDkim(dkimResult, recommendations);
        breakdown.put("DKIM", dkimScore);

        // 4. Analyse MX
        DnsRecordResult mxResult = lookupMx(domain);
        records.add(mxResult);
        int mxScore = scoreMx(mxResult, recommendations);
        breakdown.put("MX", mxScore);

        // 5. BIMI — reported but not scored. It is a brand-display feature requiring a
        // paid Verified Mark Certificate, not an email security control, and counting
        // it made a perfect score unreachable for domains that correctly skip it.
        DnsRecordResult bimiResult = lookupBimi(domain);
        records.add(bimiResult);
        scoreBimi(bimiResult, recommendations);

        // The score is the share of applicable points earned. A check that cannot be
        // determined — DKIM, whose selectors are unlistable — is dropped from both
        // sides of the ratio rather than counted as a failure.
        int earned = dmarcScore + spfScore + mxScore;
        int applicable = ScoringModel.MAX_DMARC + ScoringModel.MAX_SPF + ScoringModel.MAX_MX;

        boolean dkimDeterminable = !"indeterminate".equals(dkimResult.status());
        if (dkimDeterminable) {
            earned += dkimScore;
            applicable += ScoringModel.MAX_DKIM;
        } else {
            recommendations.add(new RecommendationDTO("info", "DKIM",
                    "DKIM could not be verified, so it is excluded from the score rather than "
                            + "counted against you.",
                    "Import this domain's DMARC reports: they name the selectors actually in use, "
                            + "and the next analysis will check those directly."));
        }

        int totalScore = applicable == 0 ? 0 : Math.round(earned * 100f / applicable);
        totalScore = Math.min(100, Math.max(0, totalScore));
        String grade = computeGrade(totalScore);
        String color = computeColor(totalScore);

        SecurityScore securityScore = new SecurityScore(totalScore, grade, color, breakdown);

        // Persist analysis
        DomainAnalysis entity = persistAnalysis(
                domain, username, organization, totalScore, grade, records, recommendations, breakdown);

        // Alerts belong to a tenant, so they are only raised for a signed-in scan.
        if (raiseAlerts && organization != null) {
            createAlertsForFindings(organization, domain, records, recommendations);
        }

        log.info("DNS analysis complete for {}: score={}, grade={}", domain, totalScore, grade);

        return new DomainAnalysisResponse(
                entity.getId(), domain, securityScore,
                records, recommendations,
                entity.getAnalyzedAt(), username
        );
    }

    public PaginatedResponse<DomainAnalysisResponse> getHistory(Long organizationId, int page, int pageSize) {
        Page<DomainAnalysis> dbPage = domainAnalysisRepository
                .findByOrganizationIdOrderByAnalyzedAtDesc(
                        organizationId, PageRequest.of(Math.max(0, page - 1), pageSize));

        List<DomainAnalysisResponse> items = dbPage.getContent().stream()
                .map(this::entityToResponse)
                .toList();

        return new PaginatedResponse<>(items, dbPage.getTotalElements(),
                page, pageSize, dbPage.getTotalPages());
    }

    /**
     * Current posture of every domain this organization has analysed, weakest first.
     *
     * <p>Built entirely from stored analyses — real DNS facts. It deliberately does
     * not invent traffic figures: those come only from ingested DMARC reports, and a
     * DNS analysis produces none.
     */
    public List<DomainPosture> getDomainPosture(Long organizationId) {
        return domainAnalysisRepository.findLatestPerDomain(organizationId).stream()
                .map(latest -> toPosture(organizationId, latest))
                .toList();
    }

    private DomainPosture toPosture(Long organizationId, DomainAnalysis latest) {
        DomainAnalysisResponse detail = entityToResponse(latest);
        Map<String, Object> dmarc = parsedFor(detail, "DMARC");

        // The score at the previous analysis, so the panel can show movement. The
        // history is newest-first, so element 1 is the one before this.
        List<DomainAnalysis> history =
                domainAnalysisRepository.findHistoryForDomain(organizationId, latest.getDomain());
        Integer previous = history.size() > 1 ? history.get(1).getScore() : null;

        return new DomainPosture(
                latest.getDomain(),
                latest.getScore(),
                latest.getGrade(),
                text(dmarc, "p"),
                integer(dmarc, "pct"),
                text(dmarc, "sp"),
                dmarc.containsKey("rua"),
                statusFor(detail, "SPF"),
                statusFor(detail, "DKIM"),
                latest.getAnalyzedAt(),
                previous,
                reportRepository.countFiltered(organizationId, latest.getDomain(), null, null));
    }

    private Map<String, Object> parsedFor(DomainAnalysisResponse detail, String type) {
        return detail.records().stream()
                .filter(r -> type.equals(r.type()) && r.parsed() != null)
                .findFirst()
                .map(DnsRecordResult::parsed)
                .orElse(Map.of());
    }

    private String statusFor(DomainAnalysisResponse detail, String type) {
        return detail.records().stream()
                .filter(r -> type.equals(r.type()))
                .findFirst()
                .map(DnsRecordResult::status)
                .orElse("unknown");
    }

    private static String text(Map<String, Object> parsed, String key) {
        Object value = parsed.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static Integer integer(Map<String, Object> parsed, String key) {
        Object value = parsed.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? null : Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Scoped fetch for signed-in callers. */
    public DomainAnalysisResponse getAnalysis(Long organizationId, Long id) {
        return domainAnalysisRepository.findByIdAndOrganizationId(id, organizationId)
                .map(this::entityToResponse)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No analysis with id " + id));
    }

    /**
     * Unscoped fetch, used only by the public scanner for analyses it owns itself
     * (those have no organization). Not reachable from an authenticated endpoint.
     */
    public DomainAnalysisResponse getPublicAnalysis(Long id) {
        return domainAnalysisRepository.findById(id)
                .map(this::entityToResponse)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No analysis with id " + id));
    }

    // ─── DNS LOOKUP METHODS ─────────────────────────────────────────

    private DnsRecordResult lookupDmarc(String domain) {
        String dmarcDomain = "_dmarc." + domain;
        try {
            Lookup lookup = new Lookup(dmarcDomain, Type.TXT);
            lookup.setResolver(new SimpleResolver("8.8.8.8"));
            lookup.setCache(null);
            Record[] results = lookup.run();

            if (results != null) {
                for (Record r : results) {
                    TXTRecord txt = (TXTRecord) r;
                    String value = String.join("", txt.getStrings());
                    if (value.toLowerCase().startsWith("v=dmarc1")) {
                        Map<String, Object> parsed = parseDmarcRecord(value);
                        return new DnsRecordResult("DMARC", "found", value, parsed,
                                "DMARC record found with policy: " + parsed.getOrDefault("p", "unknown"));
                    }
                }
            }
            return new DnsRecordResult("DMARC", "not_found", null, Map.of(),
                    "No DMARC record found for " + domain);
        } catch (Exception e) {
            log.warn("DMARC lookup error for {}: {}", domain, e.getMessage());
            return new DnsRecordResult("DMARC", "error", null,
                    Map.of("error", e.getMessage()),
                    "Error during DMARC lookup: " + e.getMessage());
        }
    }

    private DnsRecordResult lookupSpf(String domain) {
        try {
            Lookup lookup = new Lookup(domain, Type.TXT);
            lookup.setResolver(new SimpleResolver("8.8.8.8"));
            lookup.setCache(null);
            Record[] results = lookup.run();

            if (results != null) {
                for (Record r : results) {
                    TXTRecord txt = (TXTRecord) r;
                    String value = String.join("", txt.getStrings());
                    if (value.toLowerCase().startsWith("v=spf1")) {
                        Map<String, Object> parsed = parseSpfRecord(value);
                        return new DnsRecordResult("SPF", "found", value, parsed,
                                "SPF record found: " + value);
                    }
                }
            }
            return new DnsRecordResult("SPF", "not_found", null, Map.of(),
                    "No SPF record found for " + domain);
        } catch (Exception e) {
            log.warn("SPF lookup error for {}: {}", domain, e.getMessage());
            return new DnsRecordResult("SPF", "error", null,
                    Map.of("error", e.getMessage()),
                    "Error during SPF lookup: " + e.getMessage());
        }
    }

    /**
     * Looks for a DKIM key.
     *
     * <p>DKIM selectors cannot be enumerated from DNS — the only way to find one is
     * to try names and see which resolve. Selectors seen in this organization's own
     * aggregate reports are tried first, because those are facts rather than guesses;
     * a list of common provider selectors follows.
     */
    private DnsRecordResult lookupDkim(String domain, List<String> knownSelectors) {
        List<String> candidates = new ArrayList<>(knownSelectors);
        DKIM_SELECTORS.stream().filter(sel -> !candidates.contains(sel)).forEach(candidates::add);

        for (String selector : candidates) {
            String dkimDomain = selector + "._domainkey." + domain;
            try {
                Lookup lookup = new Lookup(dkimDomain, Type.TXT);
                lookup.setResolver(new SimpleResolver("8.8.8.8"));
                lookup.setCache(null);
                Record[] results = lookup.run();

                if (results != null) {
                    for (Record r : results) {
                        TXTRecord txt = (TXTRecord) r;
                        String value = String.join("", txt.getStrings());
                        if (value.toLowerCase().contains("v=dkim1") || value.contains("p=")) {
                            Map<String, Object> parsed = new LinkedHashMap<>(Map.of(
                                    "selector", selector,
                                    "domain", dkimDomain,
                                    "hasPublicKey", value.contains("p=")
                            ));
                            
                            int keySize = extractDkimKeySize(value);
                            if (keySize > 0) {
                                parsed.put("keySizeBits", keySize);
                            }

                            return new DnsRecordResult("DKIM", "found", value, parsed,
                                    "DKIM record found with selector '" + selector + "'");
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("DKIM lookup failed for selector {}.{}: {}", selector, domain, e.getMessage());
            }
        }
        // "indeterminate", not "not_found": failing to guess a selector is not
        // evidence that DKIM is absent. google.com signs with date-based selectors
        // that no fixed list can predict, and scoring that as a failure was wrong.
        return new DnsRecordResult("DKIM", "indeterminate", null,
                Map.of("selectorsChecked", candidates.size()),
                "No DKIM key found under " + candidates.size() + " common selectors. "
                        + "Selectors cannot be listed from DNS, so DKIM may still be active "
                        + "under a name not tried here.");
    }

    private DnsRecordResult lookupMx(String domain) {
        try {
            Lookup lookup = new Lookup(domain, Type.MX);
            lookup.setResolver(new SimpleResolver("8.8.8.8"));
            lookup.setCache(null);
            Record[] results = lookup.run();

            if (results != null && results.length > 0) {
                List<Map<String, Object>> mxRecords = new ArrayList<>();
                for (Record r : results) {
                    MXRecord mx = (MXRecord) r;
                    mxRecords.add(Map.of(
                            "priority", mx.getPriority(),
                            "target", mx.getTarget().toString(true)
                    ));
                }
                mxRecords.sort(Comparator.comparingInt(m -> (int) m.get("priority")));

                return new DnsRecordResult("MX", "found", null,
                        Map.of("records", mxRecords, "count", mxRecords.size()),
                        mxRecords.size() + " MX record(s) found");
            }
            return new DnsRecordResult("MX", "not_found", null, Map.of(),
                    "No MX record found for " + domain);
        } catch (Exception e) {
            log.warn("MX lookup error for {}: {}", domain, e.getMessage());
            return new DnsRecordResult("MX", "error", null,
                    Map.of("error", e.getMessage()),
                    "Error during MX lookup: " + e.getMessage());
        }
    }

    private DnsRecordResult lookupBimi(String domain) {
        String bimiDomain = "default._bimi." + domain;
        try {
            Lookup lookup = new Lookup(bimiDomain, Type.TXT);
            lookup.setResolver(new SimpleResolver("8.8.8.8"));
            lookup.setCache(null);
            Record[] results = lookup.run();

            if (results != null) {
                for (Record r : results) {
                    TXTRecord txt = (TXTRecord) r;
                    String value = String.join("", txt.getStrings());
                    if (value.toLowerCase().startsWith("v=bimi1")) {
                        Map<String, Object> parsed = parseBimiRecord(value);
                        return new DnsRecordResult("BIMI", "found", value, parsed,
                                "BIMI record found.");
                    }
                }
            }
            return new DnsRecordResult("BIMI", "not_found", null, Map.of(),
                    "No BIMI record found for 'default' selector.");
        } catch (Exception e) {
            log.warn("BIMI lookup error for {}: {}", domain, e.getMessage());
            return new DnsRecordResult("BIMI", "error", null,
                    Map.of("error", e.getMessage()),
                    "Error during BIMI lookup: " + e.getMessage());
        }
    }

    private int extractDkimKeySize(String rawRecord) {
        try {
            String[] parts = rawRecord.split(";");
            String pValue = null;
            for (String part : parts) {
                String trimmed = part.trim();
                if (trimmed.startsWith("p=")) {
                    pValue = trimmed.substring(2).trim();
                    break;
                }
            }
            if (pValue == null || pValue.isEmpty()) return 0;

            try {
                byte[] keyBytes = java.util.Base64.getDecoder().decode(pValue);
                java.security.spec.X509EncodedKeySpec spec = new java.security.spec.X509EncodedKeySpec(keyBytes);
                java.security.KeyFactory kf = java.security.KeyFactory.getInstance("RSA");
                java.security.PublicKey pubKey = kf.generatePublic(spec);
                if (pubKey instanceof java.security.interfaces.RSAPublicKey) {
                    return ((java.security.interfaces.RSAPublicKey) pubKey).getModulus().bitLength();
                }
            } catch (Exception e) {
                int len = pValue.length();
                if (len < 100) return 0;
                if (len < 300) return 1024;
                if (len < 500) return 2048;
                return 4096;
            }
        } catch (Exception e) {
            log.debug("Failed to extract DKIM key size: {}", e.getMessage());
        }
        return 0;
    }

    // ─── SCORING METHODS ────────────────────────────────────────────

    // Package-private so ScoringModelTest can hold the engine to what
    // ScoringModel advertises.
    int scoreDmarc(DnsRecordResult result, List<RecommendationDTO> recs) {
        if (!"found".equals(result.status())) {
            recs.add(new RecommendationDTO("critical", "DMARC",
                    "No DMARC record detected. Your domain is vulnerable to email spoofing.",
                    "Add a TXT record for _dmarc.yourdomain.com with at least: v=DMARC1; p=quarantine; rua=mailto:dmarc@yourdomain.com"));
            return 0;
        }

        String policy = Objects.toString(result.parsed().get("p"), "none").toLowerCase();
        int score;
        switch (policy) {
            case "reject":
                score = ScoringModel.DMARC_REJECT;
                recs.add(new RecommendationDTO("success", "DMARC",
                        "Excellent DMARC 'reject' policy: non-compliant emails are rejected.",
                        null));
                break;
            case "quarantine":
                score = ScoringModel.DMARC_QUARANTINE;
                recs.add(new RecommendationDTO("info", "DMARC",
                        "DMARC 'quarantine' policy detected. Non-compliant emails are quarantined.",
                        "Consider upgrading to 'reject' policy for maximum protection after verifying your legitimate emails pass authentication."));
                break;
            default: // "none"
                score = ScoringModel.DMARC_NONE;
                recs.add(new RecommendationDTO("warning", "DMARC",
                        "DMARC 'none' policy detected. No action is taken on non-compliant emails.",
                        "Gradually upgrade from 'none' to 'quarantine' and then 'reject' to protect your domain against spoofing."));
                break;
        }

        // A policy with no reporting address leaves the owner blind to what is being
        // sent as their domain, so it costs part of the DMARC allowance.
        if (result.parsed().containsKey("rua")) {
            recs.add(new RecommendationDTO("success", "DMARC",
                    "Aggregate reporting address (rua) configured.",
                    null));
        } else {
            recs.add(new RecommendationDTO("info", "DMARC",
                    "No aggregate reporting address (rua): you receive no visibility into "
                            + "who is sending mail as this domain.",
                    "Add rua=mailto:dmarc-reports@yourdomain.com to the DMARC record."));
            score = Math.max(0, score - ScoringModel.DMARC_NO_RUA_PENALTY);
        }

        return score;
    }

    // Package-private so ScoringModelTest can hold the engine to what
    // ScoringModel advertises.
    int scoreSpf(DnsRecordResult result, List<RecommendationDTO> recs) {
        if (!"found".equals(result.status())) {
            recs.add(new RecommendationDTO("critical", "SPF",
                    "No SPF record detected. Anyone can send emails pretending to be your domain.",
                    "Add a TXT record with: v=spf1 include:_spf.google.com -all (adapt according to your email provider)."));
            return 0;
        }

        String raw = Objects.toString(result.rawRecord(), "").toLowerCase();

        // `redirect=` delegates the whole policy to another domain's record, so the
        // effective "all" qualifier lives there. Without following it, a perfectly
        // strict record such as facebook.com's reads as having no qualifier at all.
        String effective = raw;
        String redirectTarget = redirectTarget(raw);
        if (redirectTarget != null) {
            String redirected = lookupSpfRecord(redirectTarget);
            if (redirected != null) {
                effective = redirected.toLowerCase();
                recs.add(new RecommendationDTO("info", "SPF",
                        "SPF delegates to " + redirectTarget + " via 'redirect='.",
                        "The effective policy is the one published by " + redirectTarget + "."));
            }
        }

        int score;
        if (effective.contains("-all")) {
            score = ScoringModel.SPF_HARD_FAIL;
            recs.add(new RecommendationDTO("success", "SPF",
                    "SPF ends in '-all' (hard fail): unauthorized servers are rejected.",
                    null));
        } else if (effective.contains("~all")) {
            score = ScoringModel.SPF_SOFT_FAIL;
            recs.add(new RecommendationDTO("info", "SPF",
                    "SPF ends in '~all' (soft fail): unauthorized servers are marked, not rejected.",
                    "Move to '-all' once you are confident every legitimate sender is listed."));
        } else if (effective.contains("?all")) {
            score = ScoringModel.SPF_NEUTRAL;
            recs.add(new RecommendationDTO("warning", "SPF",
                    "SPF ends in '?all' (neutral): receivers are told to take no action.",
                    "Use '~all' or '-all' so unauthorized senders are actually handled."));
        } else if (effective.contains("+all")) {
            score = ScoringModel.SPF_PASS_ALL;
            recs.add(new RecommendationDTO("critical", "SPF",
                    "SPF ends in '+all', which authorizes every server on the internet to send as your domain.",
                    "Replace '+all' with '-all' immediately."));
        } else {
            score = ScoringModel.SPF_NO_ALL;
            recs.add(new RecommendationDTO("warning", "SPF",
                    "SPF has no 'all' mechanism, so unlisted senders fall through as neutral.",
                    "End the record with '-all' or '~all'."));
        }

        // RFC 7208 caps a policy at 10 DNS-resolving mechanisms. Only include, a, mx,
        // ptr, exists and redirect count — ip4/ip6 entries cost nothing, and the
        // previous check counted spaces, so IP-heavy records were flagged wrongly.
        int lookups = countSpfDnsLookups(raw);
        if (lookups > ScoringModel.SPF_LOOKUP_LIMIT) {
            recs.add(new RecommendationDTO("critical", "SPF",
                    "SPF needs " + lookups + " DNS lookups; the limit is "
                            + ScoringModel.SPF_LOOKUP_LIMIT + ". Receivers stop evaluating "
                            + "past the limit, which makes SPF fail for legitimate mail.",
                    "Flatten or remove includes to get back under " + ScoringModel.SPF_LOOKUP_LIMIT + "."));
        } else if (lookups > ScoringModel.SPF_LOOKUP_WARNING) {
            recs.add(new RecommendationDTO("warning", "SPF",
                    "SPF uses " + lookups + " of the " + ScoringModel.SPF_LOOKUP_LIMIT
                            + " permitted DNS lookups.",
                    "There is little headroom left before SPF breaks; consider consolidating includes."));
        }

        return score;
    }

    /** Extracts the target of a {@code redirect=} modifier, if the record has one. */
    String redirectTarget(String raw) {
        Matcher matcher = SPF_REDIRECT.matcher(raw);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** Fetches a domain's SPF record, used to resolve redirects. */
    private String lookupSpfRecord(String domain) {
        try {
            Lookup lookup = new Lookup(domain, Type.TXT);
            lookup.setResolver(new SimpleResolver("8.8.8.8"));
            lookup.setCache(null);
            Record[] results = lookup.run();

            if (results != null) {
                for (Record r : results) {
                    String value = String.join("", ((TXTRecord) r).getStrings());
                    if (value.toLowerCase().startsWith("v=spf1")) {
                        return value;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("SPF redirect lookup failed for {}: {}", domain, e.getMessage());
        }
        return null;
    }

    /**
     * Counts the DNS-resolving mechanisms in an SPF record, per RFC 7208 §4.6.4.
     * Only these consume the budget of 10; {@code ip4:} and {@code ip6:} do not.
     */
    int countSpfDnsLookups(String raw) {
        int lookups = 0;
        for (String term : raw.split("\\s+")) {
            String bare = term.replaceFirst("^[+~?-]", "");
            if (bare.startsWith("include:") || bare.startsWith("exists:")
                    || bare.startsWith("redirect=") || bare.startsWith("ptr")
                    || bare.equals("a") || bare.startsWith("a:") || bare.startsWith("a/")
                    || bare.equals("mx") || bare.startsWith("mx:") || bare.startsWith("mx/")) {
                lookups++;
            }
        }
        return lookups;
    }

    // Package-private so ScoringModelTest can hold the engine to what
    // ScoringModel advertises.
    int scoreDkim(DnsRecordResult result, List<RecommendationDTO> recs) {
        // The caller drops this control from the score entirely when it is
        // indeterminate; the returned value is then unused.
        if ("indeterminate".equals(result.status())) {
            return 0;
        }
        if (!"found".equals(result.status())) {
            recs.add(new RecommendationDTO("warning", "DKIM",
                    "No DKIM key is published. Messages cannot be cryptographically signed, "
                            + "so DMARC has to rely on SPF alone — which breaks when mail is forwarded.",
                    "Enable DKIM with your email provider and publish the key under "
                            + "selector._domainkey." + "yourdomain.com."));
            return 0;
        }
        
        int keySize = result.parsed().containsKey("keySizeBits") ? (Integer) result.parsed().get("keySizeBits") : 0;
        if (keySize > 0 && keySize < ScoringModel.DKIM_MIN_KEY_BITS) {
            recs.add(new RecommendationDTO("warning", "DKIM",
                    "The DKIM key is " + keySize + " bits. Below " + ScoringModel.DKIM_MIN_KEY_BITS
                            + " bits a key is considered weak.",
                    "Ask your email provider to rotate to a " + ScoringModel.DKIM_MIN_KEY_BITS + "-bit key."));
            return ScoringModel.DKIM_WEAK_KEY;
        } else {
            recs.add(new RecommendationDTO("success", "DKIM",
                    "DKIM key published" + (keySize >= ScoringModel.DKIM_MIN_KEY_BITS ? " (" + keySize + "-bit)." : "."),
                    null));
            return ScoringModel.DKIM_STRONG;
        }
    }

    // Package-private so ScoringModelTest can hold the engine to what
    // ScoringModel advertises.
    int scoreMx(DnsRecordResult result, List<RecommendationDTO> recs) {
        if (!"found".equals(result.status())) {
            recs.add(new RecommendationDTO("warning", "MX",
                    "No MX record found. This domain cannot receive emails.",
                    "Add MX records pointing to your mail servers."));
            return 0;
        }
        recs.add(new RecommendationDTO("success", "MX",
                "MX records are published, so the domain can receive mail.",
                null));
        return ScoringModel.MX_PRESENT;
    }

    private int scoreBimi(DnsRecordResult result, List<RecommendationDTO> recs) {
        if ("found".equals(result.status())) {
            recs.add(new RecommendationDTO("success", "BIMI",
                    "BIMI is published: supporting inboxes will show your logo. "
                            + "Informational — BIMI is not part of the security score.",
                    null));
            return 0;
        } else {
            recs.add(new RecommendationDTO("info", "BIMI",
                    "No BIMI record detected. BIMI allows you to display your brand logo next to your emails in supported inboxes.",
                    "Configure a BIMI record (default._bimi.yourdomain.com) with your SVG logo and a VMC (Verified Mark Certificate) for maximum brand visibility."));
            return 0;
        }
    }

    // ─── PARSING HELPERS ────────────────────────────────────────────

    private Map<String, Object> parseDmarcRecord(String raw) {
        Map<String, Object> parsed = new LinkedHashMap<>();
        String[] parts = raw.split(";");
        for (String part : parts) {
            String trimmed = part.trim();
            int eq = trimmed.indexOf('=');
            if (eq > 0) {
                String key = trimmed.substring(0, eq).trim().toLowerCase();
                String val = trimmed.substring(eq + 1).trim();
                parsed.put(key, val);
            }
        }
        return parsed;
    }

    private Map<String, Object> parseSpfRecord(String raw) {
        Map<String, Object> parsed = new LinkedHashMap<>();
        parsed.put("raw", raw);
        List<String> mechanisms = new ArrayList<>();
        String[] parts = raw.split("\\s+");
        for (String part : parts) {
            String lower = part.toLowerCase();
            if (lower.startsWith("v=")) {
                parsed.put("version", part);
            } else if (lower.startsWith("include:")) {
                mechanisms.add(part);
            } else if (lower.equals("-all") || lower.equals("~all") || lower.equals("?all") || lower.equals("+all")) {
                parsed.put("allQualifier", lower);
            } else if (!lower.isEmpty()) {
                mechanisms.add(part);
            }
        }
        parsed.put("mechanisms", mechanisms);
        return parsed;
    }

    private Map<String, Object> parseBimiRecord(String raw) {
        Map<String, Object> parsed = new LinkedHashMap<>();
        String[] parts = raw.split(";");
        for (String part : parts) {
            String trimmed = part.trim();
            int eq = trimmed.indexOf('=');
            if (eq > 0) {
                String key = trimmed.substring(0, eq).trim().toLowerCase();
                String val = trimmed.substring(eq + 1).trim();
                parsed.put(key, val);
            }
        }
        return parsed;
    }

    // ─── GRADE AND COLOR ────────────────────────────────────────────

    // Package-private: ScoringModelTest checks the published bands against it.
    String computeGrade(int score) {
        if (score >= ScoringModel.GRADE_A_PLUS) return "A+";
        if (score >= ScoringModel.GRADE_A) return "A";
        if (score >= ScoringModel.GRADE_B) return "B";
        if (score >= ScoringModel.GRADE_C) return "C";
        if (score >= ScoringModel.GRADE_D) return "D";
        return "F";
    }

    private String computeColor(int score) {
        if (score >= ScoringModel.GRADE_A) return "green";
        if (score >= ScoringModel.GRADE_C) return "orange";
        return "red";
    }

    // ─── PERSISTENCE ────────────────────────────────────────────────

    private DomainAnalysis persistAnalysis(String domain, String username,
                                           Organization organization,
                                           int score, String grade,
                                           List<DnsRecordResult> records,
                                           List<RecommendationDTO> recommendations,
                                           Map<String, Integer> breakdown) {
        try {
            DomainAnalysis entity = DomainAnalysis.builder()
                    .organization(organization)
                    .domain(domain)
                    .score(score)
                    .grade(grade)
                    .resultsJson(objectMapper.writeValueAsString(records))
                    .recommendationsJson(objectMapper.writeValueAsString(recommendations))
                    .scoreBreakdownJson(objectMapper.writeValueAsString(breakdown))
                    .analyzedAt(LocalDateTime.now(ZoneOffset.UTC))
                    .analyzedBy(username)
                    .build();
            return domainAnalysisRepository.save(entity);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize analysis results", e);
            throw new RuntimeException("Error saving analysis", e);
        }
    }

    private DomainAnalysisResponse entityToResponse(DomainAnalysis entity) {
        try {
            List<DnsRecordResult> records = entity.getResultsJson() != null
                    ? objectMapper.readValue(entity.getResultsJson(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, DnsRecordResult.class))
                    : List.of();

            List<RecommendationDTO> recommendations = entity.getRecommendationsJson() != null
                    ? objectMapper.readValue(entity.getRecommendationsJson(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, RecommendationDTO.class))
                    : List.of();

            // Analyses stored before the breakdown was persisted have no value here,
            // so fall back to an empty map rather than failing to render.
            Map<String, Integer> breakdown = entity.getScoreBreakdownJson() != null
                    ? objectMapper.readValue(entity.getScoreBreakdownJson(),
                    objectMapper.getTypeFactory().constructMapType(
                            LinkedHashMap.class, String.class, Integer.class))
                    : Map.of();

            String color = computeColor(entity.getScore());
            SecurityScore securityScore = new SecurityScore(
                    entity.getScore(), entity.getGrade(), color, breakdown);

            return new DomainAnalysisResponse(
                    entity.getId(), entity.getDomain(), securityScore,
                    records, recommendations,
                    entity.getAnalyzedAt(), entity.getAnalyzedBy());
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize analysis results for id={}", entity.getId(), e);
            SecurityScore fallback = new SecurityScore(entity.getScore(), entity.getGrade(),
                    computeColor(entity.getScore()), Map.of());
            return new DomainAnalysisResponse(
                    entity.getId(), entity.getDomain(), fallback,
                    List.of(), List.of(),
                    entity.getAnalyzedAt(), entity.getAnalyzedBy());
        }
    }

    // ─── AUTO-ALERT CREATION ────────────────────────────────────────

    private void createAlertsForFindings(Organization organization, String domain,
                                          List<DnsRecordResult> records,
                                          List<RecommendationDTO> recommendations) {
        for (RecommendationDTO rec : recommendations) {
            if ("critical".equals(rec.severity())) {
                Alert alert = Alert.builder()
                        .organization(organization)
                        .alertType("dns_analysis")
                        .severity("critical")
                        .message("[" + domain + "] " + rec.category() + ": Missing critical configuration")
                        .details(rec.message() + (rec.action() != null ? "\n\nRecommended action: " + rec.action() : ""))
                        .domain(domain)
                        .build();
                alertRepository.save(alert);
            } else if ("warning".equals(rec.severity())) {
                Alert alert = Alert.builder()
                        .organization(organization)
                        .alertType("dns_analysis")
                        .severity("high")
                        .message("[" + domain + "] " + rec.category() + ": Configuration to improve")
                        .details(rec.message() + (rec.action() != null ? "\n\nRecommended action: " + rec.action() : ""))
                        .domain(domain)
                        .build();
                alertRepository.save(alert);
            }
        }
    }
}
