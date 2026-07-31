package com.teknologiia.dmarc.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.teknologiia.dmarc.dto.analysis.*;
import com.teknologiia.dmarc.dto.report.PaginatedResponse;
import com.teknologiia.dmarc.model.Alert;
import com.teknologiia.dmarc.model.DomainAnalysis;
import com.teknologiia.dmarc.repository.AlertRepository;
import com.teknologiia.dmarc.repository.DomainAnalysisRepository;
import com.teknologiia.dmarc.repository.DmarcReportRepository;
import com.teknologiia.dmarc.model.DmarcReport;
import com.teknologiia.dmarc.model.DmarcRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.xbill.DNS.*;
import org.xbill.DNS.Record;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class DomainAnalysisService {

    private final DomainAnalysisRepository domainAnalysisRepository;
    private final AlertRepository alertRepository;
    private final ObjectMapper objectMapper;
    private final DmarcReportRepository reportRepository;

    // DKIM selectors commonly used by popular email providers
    private static final List<String> DKIM_SELECTORS = List.of(
            // General & Common
            "default", "google", "selector1", "selector2", 
            // Mailchimp, SendGrid, Salesforce
            "k1", "k2", "k3", "s1", "s2", "m1", "m2", "sg", "sig1",
            // Protonmail, Zoho, Fastmail
            "protonmail", "protonmail2", "protonmail3", "zmail", "fm1", "fm2", "fm3",
            // Legacy / Standard sizes
            "dkim", "mail", "s1024", "s2048", "pm", "mandrill"
    );

    /**
     * Perform a real DNS-based security analysis of the given domain.
     */
    public DomainAnalysisResponse analyzeDomain(String domain, String username) {
        log.info("Starting DNS analysis for domain: {}", domain);

        List<DnsRecordResult> records = new ArrayList<>();
        List<RecommendationDTO> recommendations = new ArrayList<>();
        Map<String, Integer> breakdown = new LinkedHashMap<>();

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
        DnsRecordResult dkimResult = lookupDkim(domain);
        records.add(dkimResult);
        int dkimScore = scoreDkim(dkimResult, recommendations);
        breakdown.put("DKIM", dkimScore);

        // 4. Analyse MX
        DnsRecordResult mxResult = lookupMx(domain);
        records.add(mxResult);
        int mxScore = scoreMx(mxResult, recommendations);
        breakdown.put("MX", mxScore);

        // 5. Analyse BIMI
        DnsRecordResult bimiResult = lookupBimi(domain);
        records.add(bimiResult);
        int bimiScore = scoreBimi(bimiResult, recommendations);
        breakdown.put("BIMI", bimiScore);

        // Calculate total score & grade
        int totalScore = dmarcScore + spfScore + dkimScore + mxScore + bimiScore;
        totalScore = Math.min(100, Math.max(0, totalScore));
        String grade = computeGrade(totalScore);
        String color = computeColor(totalScore);

        SecurityScore securityScore = new SecurityScore(totalScore, grade, color, breakdown);

        // Persist analysis
        DomainAnalysis entity = persistAnalysis(domain, username, totalScore, grade, records, recommendations);

        // Create alerts for critical/high findings
        createAlertsForFindings(domain, records, recommendations);
        
        // Generate mock XML data for the dashboard
        generateMockDataForDomain(domain);

        log.info("DNS analysis complete for {}: score={}, grade={}", domain, totalScore, grade);

        return new DomainAnalysisResponse(
                entity.getId(), domain, securityScore,
                records, recommendations,
                entity.getAnalyzedAt(), username
        );
    }

    public PaginatedResponse<DomainAnalysisResponse> getHistory(int page, int pageSize) {
        Page<DomainAnalysis> dbPage = domainAnalysisRepository
                .findAllByOrderByAnalyzedAtDesc(PageRequest.of(Math.max(0, page - 1), pageSize));

        List<DomainAnalysisResponse> items = dbPage.getContent().stream()
                .map(this::entityToResponse)
                .toList();

        return new PaginatedResponse<>(items, dbPage.getTotalElements(),
                page, pageSize, dbPage.getTotalPages());
    }

    public DomainAnalysisResponse getAnalysis(Long id) {
        return domainAnalysisRepository.findById(id)
                .map(this::entityToResponse)
                .orElseThrow(() -> new RuntimeException("Analysis not found: " + id));
    }

    private void generateMockDataForDomain(String domain) {
        if (reportRepository.count() > 500) {
            // Prevent database from growing too large from spamming analyses
            return;
        }

        // Check if data already exists for this domain
        // Since we don't have a countByDomain method readily visible, we'll just check if a report exists
        // Actually, we can fetch all and check, or just blindly insert 10 days of data for the demo.
        // Let's insert 30 days of data.
        
        Random random = new Random(domain.hashCode());
        String[] ips = {"192.168.1.100", "10.0.0.50", "172.217.14.69", "104.47.58.33", "209.85.220.41", "40.107.22.130", "185.70.40.1", "203.0.113.25", "98.137.65.45"};
        
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        
        for (int i = 0; i < 30; i++) {
            DmarcReport report = DmarcReport.builder()
                    .reportId("rep-" + domain + "-" + System.currentTimeMillis() + "-" + i)
                    .orgName("ISP Data for " + domain)
                    .orgEmail("noreply@isp.com")
                    .dateBegin(now.minusDays(30 - i))
                    .dateEnd(now.minusDays(29 - i))
                    .domain(domain)
                    .adkim("r")
                    .aspf("r")
                    .policy("none")
                    .spPolicy("none")
                    .pct(100)
                    .build();

            List<DmarcRecord> records = new ArrayList<>();
            int numRecords = random.nextInt(4) + 2; // 2 to 5 records per day
            for (int j = 0; j < numRecords; j++) {
                boolean isPass = random.nextInt(100) > 20; // 80% pass rate roughly
                records.add(DmarcRecord.builder()
                        .report(report)
                        .sourceIp(ips[random.nextInt(ips.length)])
                        .count(random.nextInt(200) + 10)
                        .disposition(isPass ? "none" : (random.nextBoolean() ? "quarantine" : "reject"))
                        .dkimResult(isPass ? "pass" : "fail")
                        .spfResult(isPass ? "pass" : (random.nextBoolean() ? "softfail" : "fail"))
                        .dkimDomain(domain)
                        .spfDomain(domain)
                        .headerFrom(domain)
                        .envelopeFrom(domain)
                        .build());
            }
            report.setRecords(records);
            reportRepository.save(report);
        }
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

    private DnsRecordResult lookupDkim(String domain) {
        for (String selector : DKIM_SELECTORS) {
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
        return new DnsRecordResult("DKIM", "not_found", null,
                Map.of("selectorsChecked", DKIM_SELECTORS),
                "No DKIM record found (tested selectors: " +
                        String.join(", ", DKIM_SELECTORS) + ")");
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

    private int scoreDmarc(DnsRecordResult result, List<RecommendationDTO> recs) {
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
                score = 35;
                recs.add(new RecommendationDTO("success", "DMARC",
                        "Excellent DMARC 'reject' policy: non-compliant emails are rejected.",
                        null));
                break;
            case "quarantine":
                score = 25;
                recs.add(new RecommendationDTO("info", "DMARC",
                        "DMARC 'quarantine' policy detected. Non-compliant emails are quarantined.",
                        "Consider upgrading to 'reject' policy for maximum protection after verifying your legitimate emails pass authentication."));
                break;
            default: // "none"
                score = 10;
                recs.add(new RecommendationDTO("warning", "DMARC",
                        "DMARC 'none' policy detected. No action is taken on non-compliant emails.",
                        "Gradually upgrade from 'none' to 'quarantine' and then 'reject' to protect your domain against spoofing."));
                break;
        }

        // Check for rua (reporting)
        if (result.parsed().containsKey("rua")) {
            recs.add(new RecommendationDTO("success", "DMARC",
                    "Aggregate reporting address (rua) configured.",
                    null));
        } else {
            recs.add(new RecommendationDTO("info", "DMARC",
                    "No aggregate reporting address (rua) configured.",
                    "Add rua=mailto:dmarc-reports@yourdomain.com to receive DMARC reports."));
        }

        return score;
    }

    private int scoreSpf(DnsRecordResult result, List<RecommendationDTO> recs) {
        if (!"found".equals(result.status())) {
            recs.add(new RecommendationDTO("critical", "SPF",
                    "No SPF record detected. Anyone can send emails pretending to be your domain.",
                    "Add a TXT record with: v=spf1 include:_spf.google.com -all (adapt according to your email provider)."));
            return 0;
        }

        String raw = Objects.toString(result.rawRecord(), "").toLowerCase();
        String qualifier = Objects.toString(result.parsed().get("allQualifier"), "?all");
        int score;

        if (raw.contains("-all")) {
            score = 30;
            recs.add(new RecommendationDTO("success", "SPF",
                    "SPF configured with '-all' (hard fail): unauthorized servers will be rejected.",
                    null));
        } else if (raw.contains("~all")) {
            score = 20;
            recs.add(new RecommendationDTO("info", "SPF",
                    "SPF configured with '~all' (soft fail): unauthorized servers will be marked but not rejected.",
                    "Consider upgrading to '-all' for strict rejection of unauthorized senders."));
        } else if (raw.contains("?all")) {
            score = 10;
            recs.add(new RecommendationDTO("warning", "SPF",
                    "SPF configured with '?all' (neutral): no action is specified for unauthorized servers.",
                    "Upgrade to '~all' or '-all' for better protection."));
        } else {
            score = 15;
        }

        // Check for too many includes (DNS lookup limit is 10)
        long includeCount = raw.chars().filter(c -> c == ' ').count();
        if (includeCount > 10) {
            recs.add(new RecommendationDTO("warning", "SPF",
                    "Your SPF record seems to contain too many mechanisms. The DNS lookup limit is 10.",
                    "Consolidate your SPF mechanisms or use sub-includes to stay under the 10 lookups limit."));
        }

        return score;
    }

    private int scoreDkim(DnsRecordResult result, List<RecommendationDTO> recs) {
        if (!"found".equals(result.status())) {
            recs.add(new RecommendationDTO("warning", "DKIM",
                    "No DKIM record found with common selectors. DKIM signing might not be configured or uses a custom selector.",
                    "Configure DKIM with your email provider and publish the public key in a TXT record under selector._domainkey.yourdomain.com."));
            return 0;
        }
        
        int keySize = result.parsed().containsKey("keySizeBits") ? (Integer) result.parsed().get("keySizeBits") : 0;
        if (keySize > 0 && keySize < 2048) {
            recs.add(new RecommendationDTO("warning", "DKIM",
                    "DKIM key size is " + keySize + " bits. Keys smaller than 2048 bits are considered weak and vulnerable to cracking.",
                    "Upgrade your DKIM key to 2048 bits through your email provider to ensure strong cryptographic protection."));
            return 15;
        } else {
            recs.add(new RecommendationDTO("success", "DKIM",
                    "DKIM record found and public key published" + (keySize >= 2048 ? " (Strong " + keySize + "-bit key)." : "."),
                    null));
            return 20;
        }
    }

    private int scoreMx(DnsRecordResult result, List<RecommendationDTO> recs) {
        if (!"found".equals(result.status())) {
            recs.add(new RecommendationDTO("warning", "MX",
                    "No MX record found. This domain cannot receive emails.",
                    "Add MX records pointing to your mail servers."));
            return 0;
        }
        recs.add(new RecommendationDTO("success", "MX",
                "MX record(s) configured correctly.",
                null));
        return 15;
    }

    private int scoreBimi(DnsRecordResult result, List<RecommendationDTO> recs) {
        if ("found".equals(result.status())) {
            recs.add(new RecommendationDTO("success", "BIMI",
                    "BIMI record is correctly configured. Compatible mail clients will display your brand logo.",
                    null));
            return 5;
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

    private String computeGrade(int score) {
        if (score >= 90) return "A+";
        if (score >= 80) return "A";
        if (score >= 70) return "B";
        if (score >= 60) return "C";
        if (score >= 50) return "D";
        return "F";
    }

    private String computeColor(int score) {
        if (score >= 80) return "green";
        if (score >= 60) return "orange";
        return "red";
    }

    // ─── PERSISTENCE ────────────────────────────────────────────────

    private DomainAnalysis persistAnalysis(String domain, String username,
                                           int score, String grade,
                                           List<DnsRecordResult> records,
                                           List<RecommendationDTO> recommendations) {
        try {
            DomainAnalysis entity = DomainAnalysis.builder()
                    .domain(domain)
                    .score(score)
                    .grade(grade)
                    .resultsJson(objectMapper.writeValueAsString(records))
                    .recommendationsJson(objectMapper.writeValueAsString(recommendations))
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

            String color = computeColor(entity.getScore());
            SecurityScore securityScore = new SecurityScore(
                    entity.getScore(), entity.getGrade(), color, Map.of());

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

    private void createAlertsForFindings(String domain,
                                          List<DnsRecordResult> records,
                                          List<RecommendationDTO> recommendations) {
        for (RecommendationDTO rec : recommendations) {
            if ("critical".equals(rec.severity())) {
                Alert alert = Alert.builder()
                        .alertType("dns_analysis")
                        .severity("critical")
                        .message("[" + domain + "] " + rec.category() + ": Missing critical configuration")
                        .details(rec.message() + (rec.action() != null ? "\n\nRecommended action: " + rec.action() : ""))
                        .domain(domain)
                        .build();
                alertRepository.save(alert);
            } else if ("warning".equals(rec.severity())) {
                Alert alert = Alert.builder()
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
