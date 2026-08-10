package com.teknologiia.dmarc.service;

import com.teknologiia.dmarc.dto.analysis.TransportSecurityResult.Declared;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.Record;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.TXTRecord;
import org.xbill.DNS.Type;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What a domain publishes about TLS for its incoming mail.
 *
 * <p>Three declarations, all readable from DNS and HTTPS, so this half of the check
 * works from anywhere — unlike connecting to port 25, which most hosting providers
 * block outbound.
 *
 * <ul>
 *   <li><strong>MTA-STS</strong> (RFC 8461) — the domain telling senders that mail
 *       must go over TLS to a named set of MX hosts, with a valid certificate. In
 *       {@code enforce} mode a sender that cannot get that must not deliver at all,
 *       which is a stronger statement than any certificate being valid today.
 *   <li><strong>TLS-RPT</strong> (RFC 8460) — where to send reports when that fails.
 *       The {@code rua=} of transport security.
 *   <li><strong>DANE</strong> (RFC 7672) — handled beside this, since it is a
 *       per-host record rather than a per-domain one.
 * </ul>
 *
 * <p>MTA-STS deliberately takes two steps: a TXT record announcing that a policy
 * exists and its version id, and the policy itself over HTTPS at a well-known
 * address. Both are needed — a TXT record pointing at a policy that does not fetch
 * is a domain that believes it is protected and is not.
 */
@Component
@Slf4j
public class MtaStsClient {

    /**
     * The policy is a small text file. Anything larger is not one, and reading it
     * anyway would let a hostile host feed this process as much as it liked.
     */
    private static final int MAX_POLICY_BYTES = 64 * 1024;

    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            // The policy address is fixed by the RFC. A redirect away from it is not
            // part of the protocol, and following one would let a domain point this
            // fetch anywhere it liked.
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public Declared check(String domain) {
        String stsRecord = txt("_mta-sts." + domain, "v=STSv1");
        String rptRecord = txt("_smtp._tls." + domain, "v=TLSRPTv1");

        boolean hasRecord = stsRecord != null;
        String id = hasRecord ? tag(stsRecord, "id") : null;

        String mode = null;
        List<String> mxPatterns = List.of();
        Long maxAgeDays = null;
        String policyError = null;
        boolean policyFetched = false;

        if (hasRecord) {
            try {
                Map<String, List<String>> policy = fetchPolicy(domain);
                policyFetched = !policy.isEmpty();
                mode = first(policy.get("mode"));
                mxPatterns = policy.getOrDefault("mx", List.of());
                String maxAge = first(policy.get("max_age"));
                if (maxAge != null) {
                    maxAgeDays = Long.parseLong(maxAge.trim()) / 86_400;
                }
            } catch (Exception e) {
                // Recorded rather than swallowed: a TXT record with no reachable
                // policy is worse than no record at all, because the domain believes
                // it is enforcing something.
                policyError = e.getMessage();
                log.debug("MTA-STS policy for {} could not be read: {}", domain, e.getMessage());
            }
        }

        return new Declared(hasRecord, policyFetched, mode, id, mxPatterns, maxAgeDays,
                rptRecord != null, rptRecord == null ? null : tag(rptRecord, "rua"), policyError);
    }

    /**
     * Fetches and parses the policy file.
     *
     * <p>Keys may repeat — {@code mx} appears once per pattern — so the parse keeps a
     * list per key rather than the last value seen. Reading only the last {@code mx}
     * would report a domain with four mail servers as having one.
     */
    private Map<String, List<String>> fetchPolicy(String domain) throws Exception {
        URI uri = URI.create("https://mta-sts." + domain + "/.well-known/mta-sts.txt");

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header("User-Agent", "Teknologiia-DMARC-Dashboard")
                .GET()
                .build();

        HttpResponse<InputStream> response =
                http.send(request, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            throw new IllegalStateException("policy returned HTTP " + response.statusCode());
        }

        String body;
        try (InputStream stream = response.body()) {
            byte[] bytes = stream.readNBytes(MAX_POLICY_BYTES);
            body = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }

        Map<String, List<String>> parsed = new LinkedHashMap<>();
        for (String line : body.split("\\R")) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (!value.isEmpty()) {
                parsed.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
            }
        }

        if (!parsed.containsKey("version")) {
            throw new IllegalStateException("policy has no version line");
        }
        return parsed;
    }

    /** The first TXT record at this name whose value starts with the marker. */
    private String txt(String name, String marker) {
        try {
            Lookup lookup = new Lookup(name, Type.TXT);
            lookup.setResolver(new SimpleResolver("8.8.8.8"));
            lookup.setCache(null);
            Record[] results = lookup.run();
            if (results == null) {
                return null;
            }
            for (Record record : results) {
                String value = String.join("", ((TXTRecord) record).getStrings());
                if (value.toLowerCase(Locale.ROOT).startsWith(marker.toLowerCase(Locale.ROOT))) {
                    return value;
                }
            }
        } catch (Exception e) {
            log.debug("TXT lookup failed for {}: {}", name, e.getMessage());
        }
        return null;
    }

    /** {@code v=STSv1; id=20210803T010101;} → the value of one tag. */
    static String tag(String record, String name) {
        for (String part : record.split(";")) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2 && pair[0].trim().equalsIgnoreCase(name)) {
                return pair[1].trim();
            }
        }
        return null;
    }

    private static String first(List<String> values) {
        return values == null || values.isEmpty() ? null : values.get(0);
    }
}
