package com.teknologiia.dmarc.service;

import com.teknologiia.dmarc.model.EmailVerificationToken;
import com.teknologiia.dmarc.model.Organization;
import com.teknologiia.dmarc.model.User;
import com.teknologiia.dmarc.repository.EmailVerificationTokenRepository;
import com.teknologiia.dmarc.repository.OrganizationRepository;
import com.teknologiia.dmarc.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Redeeming an emailed verification link.
 *
 * <p>The three ways it can fail are not the same thing, and the status code has to
 * separate them. A spent token means somebody already followed the link, so the
 * account is active — reporting that as a failure alarms people about an account
 * that is working. The dashboard distinguishes them by status, so the codes are
 * part of the contract rather than an implementation detail.
 */
@SpringBootTest
@ActiveProfiles("test")
class VerificationTokenTest {

    @Autowired private RegistrationService registrationService;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private EmailVerificationTokenRepository tokenRepository;

    private User user;
    private String username;

    /**
     * Creates only what this test needs.
     *
     * <p>The Spring context — and its in-memory database — is shared with the other
     * {@code @SpringBootTest} classes, so wiping tables here tore rows out from under
     * them. Unique names per run keep the tests independent without any cleanup.
     */
    @BeforeEach
    void seed() {
        username = "pending-" + java.util.UUID.randomUUID().toString().substring(0, 8);

        Organization organization = organizationRepository.save(
                Organization.builder().name("Acme " + username).build());
        user = userRepository.save(User.builder()
                .organization(organization)
                .username(username)
                .email(username + "@acme.test")
                .hashedPassword("x")
                .role("ADMIN")
                .active(false)
                .build());
    }

    private String token(String value, LocalDateTime expiresAt, LocalDateTime usedAt) {
        tokenRepository.save(EmailVerificationToken.builder()
                .token(value).user(user).expiresAt(expiresAt).usedAt(usedAt).build());
        return value;
    }

    private LocalDateTime hoursFromNow(long hours) {
        return LocalDateTime.now(ZoneOffset.UTC).plusHours(hours);
    }

    @Test
    @DisplayName("a fresh link activates the account")
    void freshLinkActivates() {
        registrationService.verify(token(username + "-fresh", hoursFromNow(24), null));

        assertThat(userRepository.findByUsername(username)).get()
                .extracting(User::isActive).isEqualTo(true);
        assertThat(tokenRepository.findByToken(username + "-fresh")).get()
                .extracting(EmailVerificationToken::getUsedAt).isNotNull();
    }

    @Test
    @DisplayName("a spent link answers 409, because the account is already active")
    void spentLinkIsAConflict() {
        String spent = token(username + "-spent", hoursFromNow(24), LocalDateTime.now(ZoneOffset.UTC));

        assertThatThrownBy(() -> registrationService.verify(spent))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("redeeming twice leaves the account active and reports the conflict")
    void redeemingTwiceIsSafe() {
        String once = token(username + "-once", hoursFromNow(24), null);
        registrationService.verify(once);

        assertThatThrownBy(() -> registrationService.verify(once))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        // The second attempt must not undo the first.
        assertThat(userRepository.findByUsername(username)).get()
                .extracting(User::isActive).isEqualTo(true);
    }

    @Test
    @DisplayName("an expired link answers 400 and leaves the account disabled")
    void expiredLinkIsABadRequest() {
        String stale = token(username + "-stale", hoursFromNow(-1), null);

        assertThatThrownBy(() -> registrationService.verify(stale))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(userRepository.findByUsername(username)).get()
                .extracting(User::isActive).isEqualTo(false);
    }

    @Test
    @DisplayName("an unknown link answers 400")
    void unknownLinkIsABadRequest() {
        assertThatThrownBy(() -> registrationService.verify("never-issued"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
