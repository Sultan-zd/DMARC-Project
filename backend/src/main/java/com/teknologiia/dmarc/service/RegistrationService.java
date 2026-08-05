package com.teknologiia.dmarc.service;

import com.teknologiia.dmarc.dto.auth.RegisterRequest;
import com.teknologiia.dmarc.model.EmailVerificationToken;
import com.teknologiia.dmarc.model.Organization;
import com.teknologiia.dmarc.model.User;
import com.teknologiia.dmarc.repository.EmailVerificationTokenRepository;
import com.teknologiia.dmarc.repository.OrganizationRepository;
import com.teknologiia.dmarc.repository.UserRepository;
import com.teknologiia.dmarc.security.PasswordPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;

/**
 * Self-service sign-up.
 *
 * <p>Registering creates an organization and its first administrator in one step,
 * with the account left disabled until its email address is verified.
 *
 * <p><strong>Delivery:</strong> no mail is sent yet. The verification link is written
 * to the application log instead, so the flow is complete and testable without an
 * SMTP server. Swapping the log line for a real send is the only change needed.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RegistrationService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final EmailVerificationTokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final OrganizationDomainService domainService;
    private final OutboundMailService mailService;

    @Value("${app.registration.token-ttl-hours:24}")
    private long tokenTtlHours;

    @Value("${app.public-url:http://localhost:5173}")
    private String publicUrl;

    /**
     * Creates the tenant, its first administrator, and a verification token.
     *
     * @return the token, so tests and the development flow can complete the journey
     */
    @Transactional
    public String register(RegisterRequest request) {
        String username = request.username().trim();
        String email = request.email().trim().toLowerCase();

        if (userRepository.existsByUsername(username)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That username is already taken.");
        }
        if (userRepository.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That email address is already registered.");
        }

        // One definition of an acceptable password, shared with account creation
        // and self-service changes.
        passwordPolicy.enforce(request.password(), username, email);

        // If a organization has proven ownership of this email domain, the person
        // signing up is one of their colleagues — join that organization instead of
        // starting a second one with the same company name and an empty dashboard.
        var claimed = domainService.ownerOf(email);

        Organization organization;
        String role;
        if (claimed.isPresent()) {
            organization = claimed.get().getOrganization();
            role = claimed.get().getDefaultRole();
            log.info("{} joins existing organization '{}' via verified domain {}",
                    email, organization.getName(), claimed.get().getDomain());
        } else {
            organization = organizationRepository.save(
                    Organization.builder().name(request.organization().trim()).build());
            // Whoever creates an organization administers it.
            role = "ADMIN";
        }

        // Disabled until verified: Spring Security refuses to authenticate the
        // account, so an unverified sign-up yields nothing usable.
        User user = userRepository.save(User.builder()
                .organization(organization)
                .username(username)
                .email(email)
                .hashedPassword(passwordEncoder.encode(request.password()))
                .role(role)
                .active(false)
                .build());

        String token = newToken();
        tokenRepository.save(EmailVerificationToken.builder()
                .token(token)
                .user(user)
                .expiresAt(LocalDateTime.now(ZoneOffset.UTC).plus(Duration.ofHours(tokenTtlHours)))
                .build());

        // The account stays disabled until this address answers, which is the whole
        // point of the message. Sending is asynchronous and failures are logged with
        // the link, so a mail outage never leaves a sign-up with no way through.
        mailService.sendVerification(email, username, token);

        return token;
    }

    /** Redeems a verification token and enables the account. */
    @Transactional
    public void verify(String token) {
        EmailVerificationToken record = tokenRepository.findByToken(token)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "This verification link is not valid."));

        if (record.getUsedAt() != null) {
            // 409 rather than 400: a spent token means somebody already followed this
            // link, so the account it belongs to is active. That is a different
            // situation from a link that never worked, and the dashboard says so
            // instead of reporting a failure over an account that is perfectly fine.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This verification link has already been used.");
        }

        if (!record.isUsable()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "This verification link has expired. Request a new one.");
        }

        User user = record.getUser();
        user.setActive(true);
        userRepository.save(user);

        record.setUsedAt(LocalDateTime.now(ZoneOffset.UTC));
        tokenRepository.save(record);

        log.info("Account {} verified", user.getUsername());
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
