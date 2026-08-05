package com.teknologiia.dmarc.service;

import com.teknologiia.dmarc.model.Organization;
import com.teknologiia.dmarc.model.OrganizationDomain;
import com.teknologiia.dmarc.repository.OrganizationDomainRepository;
import com.teknologiia.dmarc.repository.OrganizationRepository;
import com.teknologiia.dmarc.web.DomainNameValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.xbill.DNS.*;
import org.xbill.DNS.Record;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Claiming and proving ownership of an email domain.
 *
 * <p>A verified claim is what lets a colleague signing up with a company address
 * land in the company's existing organization rather than a new one.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrganizationDomainService {

    /**
     * Domains nobody may claim. Whoever claimed one of these would absorb every
     * future sign-up using that provider — the first organization to ask would
     * silently acquire strangers' accounts.
     */
    private static final Set<String> PUBLIC_PROVIDERS = Set.of(
            "gmail.com", "googlemail.com", "outlook.com", "hotmail.com", "live.com",
            "msn.com", "yahoo.com", "yahoo.fr", "ymail.com", "aol.com",
            "icloud.com", "me.com", "mac.com", "proton.me", "protonmail.com",
            "gmx.com", "gmx.net", "mail.com", "zoho.com", "yandex.com",
            "orange.fr", "free.fr", "wanadoo.fr", "sfr.fr", "laposte.net",
            "qq.com", "163.com", "126.com", "naver.com", "seznam.cz"
    );

    private static final SecureRandom RANDOM = new SecureRandom();

    private final OrganizationDomainRepository domainRepository;
    private final OrganizationRepository organizationRepository;

    public List<OrganizationDomain> list(Long organizationId) {
        return domainRepository.findByOrganizationIdOrderByCreatedAtAsc(organizationId);
    }

    /**
     * Registers a claim and returns it with the token to publish. The claim confers
     * nothing until {@link #verify} finds that token in DNS.
     */
    @Transactional
    public OrganizationDomain claim(Long organizationId, String rawDomain, String defaultRole) {
        String domain = DomainNameValidator.normalise(rawDomain);

        if (PUBLIC_PROVIDERS.contains(domain)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That is a public email provider. Claim a domain your organization owns.");
        }

        domainRepository.findByDomainIgnoreCase(domain).ifPresent(existing -> {
            if (!existing.getOrganization().getId().equals(organizationId)) {
                // Deliberately vague: confirming who holds it would leak the existence
                // and identity of another customer.
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "That domain is already claimed. Contact support if it belongs to you.");
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "You have already claimed that domain.");
        });

        Organization organization = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR, "Organization not found."));

        String role = defaultRole == null ? "VIEWER" : defaultRole.trim().toUpperCase(Locale.ROOT);
        if (!UserService.ROLES.contains(role)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Role must be one of ADMIN, ANALYST or VIEWER.");
        }

        return domainRepository.save(OrganizationDomain.builder()
                .organization(organization)
                .domain(domain)
                .verificationToken("teknologiia-verify=" + randomToken())
                .defaultRole(role)
                .build());
    }

    /** Looks for the claim's token in DNS and marks it verified when found. */
    @Transactional
    public OrganizationDomain verify(Long organizationId, Long domainId) {
        OrganizationDomain claim = domainRepository.findByIdAndOrganizationId(domainId, organizationId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No such domain claim."));

        if (claim.isVerified()) {
            return claim;
        }
        if (!txtRecordsFor(claim.verificationHost()).contains(claim.getVerificationToken())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The verification record was not found. Publish a TXT record at "
                            + claim.verificationHost() + " containing "
                            + claim.getVerificationToken() + ", then try again. "
                            + "DNS changes can take a few minutes to propagate.");
        }

        claim.setVerifiedAt(LocalDateTime.now(ZoneOffset.UTC));
        log.info("Organization {} verified ownership of {}", organizationId, claim.getDomain());
        return domainRepository.save(claim);
    }

    @Transactional
    public void release(Long organizationId, Long domainId) {
        OrganizationDomain claim = domainRepository.findByIdAndOrganizationId(domainId, organizationId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No such domain claim."));
        domainRepository.delete(claim);
    }

    /**
     * The organization that has proven ownership of the address's domain, if any.
     * This is what registration consults before creating a new organization.
     */
    public Optional<OrganizationDomain> ownerOf(String email) {
        if (email == null || !email.contains("@")) {
            return Optional.empty();
        }
        String domain = email.substring(email.indexOf('@') + 1).trim().toLowerCase(Locale.ROOT);
        if (domain.isEmpty() || PUBLIC_PROVIDERS.contains(domain)) {
            return Optional.empty();
        }
        return domainRepository.findByDomainIgnoreCaseAndVerifiedAtIsNotNull(domain);
    }

    private List<String> txtRecordsFor(String host) {
        try {
            Lookup lookup = new Lookup(host, Type.TXT);
            lookup.setResolver(new SimpleResolver("8.8.8.8"));
            lookup.setCache(null);
            Record[] results = lookup.run();

            if (results == null) {
                return List.of();
            }
            return java.util.Arrays.stream(results)
                    .map(r -> String.join("", ((TXTRecord) r).getStrings()))
                    .toList();
        } catch (Exception e) {
            log.warn("Verification lookup failed for {}: {}", host, e.getMessage());
            return List.of();
        }
    }

    private static String randomToken() {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
