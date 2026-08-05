package com.teknologiia.dmarc.controller;

import com.teknologiia.dmarc.dto.analysis.DomainAnalysisRequest;
import com.teknologiia.dmarc.dto.analysis.DomainAnalysisResponse;
import com.teknologiia.dmarc.service.PublicScanService;
import com.teknologiia.dmarc.web.DomainNameValidator;
import com.teknologiia.dmarc.web.ClientAddress;
import com.teknologiia.dmarc.web.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * The unauthenticated domain scanner behind the public landing page.
 *
 * <p>This is the only endpoint that spends server resources for an anonymous
 * caller, so every request passes a rate limit and a domain validator before any
 * DNS query is issued.
 */
@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
@Slf4j
public class PublicScanController {

    private final PublicScanService scanService;
    private final RateLimiter rateLimiter;
    private final ClientAddress clientAddress;

    @PostMapping("/scan")
    public ResponseEntity<?> scan(@RequestBody DomainAnalysisRequest request, HttpServletRequest http) {
        String domain;
        try {
            domain = DomainNameValidator.normalise(request.domain());
        } catch (DomainNameValidator.InvalidDomainException e) {
            return ResponseEntity.badRequest().body(Map.of("detail", e.getMessage()));
        }

        RateLimiter.Decision decision = rateLimiter.tryAcquire(clientAddress.of(http));
        if (!decision.allowed()) {
            log.info("Rate limited public scan of {}", domain);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(decision.retryAfterSeconds()))
                    .body(Map.of(
                            "detail", "Too many scans from this address. Try again in "
                                    + decision.retryAfterSeconds() + " seconds.",
                            "retry_after_seconds", decision.retryAfterSeconds()));
        }

        DomainAnalysisResponse result = scanService.scan(domain);
        return ResponseEntity.ok()
                .header("X-RateLimit-Remaining", String.valueOf(decision.remaining()))
                .body(result);
    }

    /**
     * Reads back the latest stored result for a domain. Shared links resolve here,
     * and this never triggers a fresh lookup, so a link cannot be used to generate
     * DNS traffic on demand.
     */
    @GetMapping("/scan/{domain}")
    public ResponseEntity<?> lastResult(@PathVariable String domain) {
        String normalised;
        try {
            normalised = DomainNameValidator.normalise(domain);
        } catch (DomainNameValidator.InvalidDomainException e) {
            return ResponseEntity.badRequest().body(Map.of("detail", e.getMessage()));
        }

        return scanService.lastResult(normalised)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("detail", "No scan recorded for " + normalised + " yet.")));
    }

}
