package com.teknologiia.dmarc.service;

import com.teknologiia.dmarc.dto.analysis.DomainAnalysisResponse;
import com.teknologiia.dmarc.model.DomainAnalysis;
import com.teknologiia.dmarc.repository.DomainAnalysisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Serves the anonymous domain scan behind the public landing page.
 *
 * <p>A scan costs a handful of outbound DNS queries, so a recent result for the
 * same domain is reused rather than re-resolved. That keeps a shared link cheap
 * to open and blunts repeated scans of the same target.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PublicScanService {

    /** Recorded as the author of anonymous scans, to tell them apart in history. */
    public static final String ANONYMOUS = "public";

    private final DomainAnalysisService analysisService;
    private final DomainAnalysisRepository analysisRepository;

    @Value("${app.public-scan.cache-minutes:15}")
    private long cacheMinutes;

    /**
     * Returns an analysis of {@code domain}, reusing a recent one when available.
     * The domain must already be normalised.
     */
    public DomainAnalysisResponse scan(String domain) {
        Optional<DomainAnalysis> cached = findFresh(domain);
        if (cached.isPresent()) {
            log.debug("Serving cached scan for {}", domain);
            return analysisService.getPublicAnalysis(cached.get().getId());
        }

        // No organization and no alerts: an anonymous scan belongs to no tenant and
        // must not surface in any team's alert feed.
        return analysisService.analyzeDomain(domain, ANONYMOUS, null, false);
    }

    /**
     * Returns the most recent stored analysis of a domain without ever running a
     * new one. Backs shareable links, so opening a shared result cannot be used to
     * drive DNS traffic.
     */
    public Optional<DomainAnalysisResponse> lastResult(String domain) {
        // Anonymous rows only: an organization's own analysis of the same domain is
        // private, and its `analyzed_by` would leak an internal username.
        return analysisRepository.findFirstByDomainAndOrganizationIsNullOrderByAnalyzedAtDesc(domain)
                .map(analysis -> analysisService.getPublicAnalysis(analysis.getId()));
    }

    private Optional<DomainAnalysis> findFresh(String domain) {
        if (cacheMinutes <= 0) {
            return Optional.empty();
        }
        LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC).minus(Duration.ofMinutes(cacheMinutes));

        return analysisRepository.findFirstByDomainAndOrganizationIsNullOrderByAnalyzedAtDesc(domain)
                .filter(analysis -> analysis.getAnalyzedAt() != null
                        && analysis.getAnalyzedAt().isAfter(cutoff));
    }
}
