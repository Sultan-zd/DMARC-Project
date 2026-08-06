package com.teknologiia.dmarc.service;

import com.teknologiia.dmarc.model.Organization;
import com.teknologiia.dmarc.model.PasswordResetToken;
import com.teknologiia.dmarc.model.User;
import com.teknologiia.dmarc.repository.OrganizationRepository;
import com.teknologiia.dmarc.repository.PasswordResetTokenRepository;
import com.teknologiia.dmarc.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Recovering an account without a password.
 *
 * <p>The flow accepts one thing as evidence — that a link sent to the registered
 * mailbox came back — so everything here is about that link: it works once, it
 * expires, asking for a new one kills the old one, and the endpoint that issues it
 * says nothing about whether the address is registered at all.
 */
@SpringBootTest
@ActiveProfiles("test")
class PasswordResetTest {

    private static final String OLD_PASSWORD = "Grey-Harbour-Lantern-72";
    private static final String NEW_PASSWORD = "Copper-Meridian-Vault-58";

    @Autowired private PasswordResetService resetService;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private PasswordResetTokenRepository tokenRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private String username;
    private String email;

    @BeforeEach
    void seed() {
        String id = UUID.randomUUID().toString().substring(0, 8);
        username = "reset-" + id;
        email = id + "@reset.test";

        Organization organization = organizationRepository.save(
                Organization.builder().name("Reset " + id).build());
        userRepository.save(User.builder()
                .organization(organization)
                .username(username)
                .email(email)
                .hashedPassword(passwordEncoder.encode(OLD_PASSWORD))
                .role("ADMIN")
                .active(true)
                .build());
    }

    private User reload() {
        return userRepository.findByUsername(username).orElseThrow();
    }

    // ─── The happy path ─────────────────────────────────────────────

    @Test
    @DisplayName("a link sent to the registered address sets a new password")
    void resetsThePassword() {
        String token = resetService.requestReset(email).orElseThrow();

        resetService.reset(token, NEW_PASSWORD);

        User user = reload();
        assertThat(passwordEncoder.matches(NEW_PASSWORD, user.getHashedPassword())).isTrue();
        assertThat(passwordEncoder.matches(OLD_PASSWORD, user.getHashedPassword()))
                .as("the old password must stop working")
                .isFalse();
    }

    @Test
    @DisplayName("the address is matched however it is typed")
    void addressIsCaseInsensitive() {
        // Stored lowercased at sign-up, typed however the person types it. Someone
        // who cannot remember their password should not also have to remember
        // whether they capitalised their own email.
        assertThat(resetService.requestReset(email.toUpperCase())).isPresent();
    }

    // ─── What the link is worth ─────────────────────────────────────

    @Test
    @DisplayName("a link works once")
    void tokenIsSingleUse() {
        String token = resetService.requestReset(email).orElseThrow();
        resetService.reset(token, NEW_PASSWORD);

        assertThatThrownBy(() -> resetService.reset(token, "Second-Attempt-Passphrase-91"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already been used");

        // And the password is the one set the first time, not the second.
        assertThat(passwordEncoder.matches(NEW_PASSWORD, reload().getHashedPassword())).isTrue();
    }

    @Test
    @DisplayName("an expired link is refused")
    void expiredTokenIsRefused() {
        String token = resetService.requestReset(email).orElseThrow();

        PasswordResetToken record = tokenRepository.findByToken(token).orElseThrow();
        record.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        tokenRepository.save(record);

        assertThatThrownBy(() -> resetService.reset(token, NEW_PASSWORD))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("expired");

        assertThat(passwordEncoder.matches(OLD_PASSWORD, reload().getHashedPassword())).isTrue();
    }

    @Test
    @DisplayName("asking for a new link takes the old one away")
    void newRequestInvalidatesTheOldLink() {
        // The reason somebody asks twice is usually that the first link is on a
        // device they no longer have. Leaving it working would defeat the request.
        String first = resetService.requestReset(email).orElseThrow();
        String second = resetService.requestReset(email).orElseThrow();

        assertThatThrownBy(() -> resetService.reset(first, NEW_PASSWORD))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already been used");

        resetService.reset(second, NEW_PASSWORD);
        assertThat(passwordEncoder.matches(NEW_PASSWORD, reload().getHashedPassword())).isTrue();
    }

    @Test
    @DisplayName("completing a reset spends every other outstanding link")
    void resetSpendsSiblings() {
        String token = resetService.requestReset(email).orElseThrow();
        resetService.reset(token, NEW_PASSWORD);

        assertThat(tokenRepository.findByUserAndUsedAtIsNull(reload()))
                .as("recovering an account should not leave a second key under the mat")
                .isEmpty();
    }

    @Test
    @DisplayName("a token that was never issued is refused")
    void unknownTokenIsRefused() {
        assertThatThrownBy(() -> resetService.reset("not-a-real-token", NEW_PASSWORD))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not valid");
    }

    // ─── What the endpoint gives away ───────────────────────────────

    @Test
    @DisplayName("an address with no account is answered the same way, silently")
    void unknownAddressIsNotDistinguished() {
        // No throw, no token, no row. The controller returns the same sentence
        // either way, so this endpoint cannot be used to ask whether a given person
        // has an account here.
        long before = tokenRepository.count();

        Optional<String> issued =
                resetService.requestReset("nobody-" + UUID.randomUUID() + "@nowhere.test");

        assertThat(issued).isEmpty();
        assertThat(tokenRepository.count())
                .as("nothing should be written for an address that does not exist")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("a blank address is not an error either")
    void blankAddressIsSilent() {
        assertThat(resetService.requestReset("")).isEmpty();
        assertThat(resetService.requestReset(null)).isEmpty();
    }

    // ─── What completing it changes ─────────────────────────────────

    @Test
    @DisplayName("the new password is held to the same policy as any other")
    void policyStillApplies() {
        String token = resetService.requestReset(email).orElseThrow();

        assertThatThrownBy(() -> resetService.reset(token, "password"))
                .isInstanceOf(ResponseStatusException.class);

        // Refused, and the link is still usable — a rejected password must not cost
        // somebody their only way back in.
        assertThat(tokenRepository.findByToken(token).orElseThrow().isUsable()).isTrue();
    }

    @Test
    @DisplayName("a forced password change is satisfied by resetting")
    void clearsMustChangePassword() {
        User user = reload();
        user.setMustChangePassword(true);
        userRepository.save(user);

        resetService.reset(resetService.requestReset(email).orElseThrow(), NEW_PASSWORD);

        assertThat(reload().isMustChangePassword()).isFalse();
    }

    @Test
    @DisplayName("an account never confirmed is activated by resetting")
    void activatesAnUnconfirmedAccount() {
        // A sign-up whose confirmation link expired leaves an account nobody can
        // enable: there is no way to ask for another. Completing this flow proves
        // control of the very mailbox that confirmation was waiting on — the same
        // evidence by a different route — so the account stops being stranded.
        User user = reload();
        user.setActive(false);
        userRepository.save(user);

        resetService.reset(resetService.requestReset(email).orElseThrow(), NEW_PASSWORD);

        assertThat(reload().isActive()).isTrue();
    }

    @Test
    @DisplayName("resetting does not turn off a second factor")
    void twoFactorSurvives() {
        // Otherwise the reset flow would be a way around it: control of a mailbox
        // would defeat a factor whose whole purpose is to survive that.
        User user = reload();
        user.setTotpSecret("JBSWY3DPEHPK3PXP");
        user.setTotpEnabledAt(LocalDateTime.now(ZoneOffset.UTC));
        userRepository.save(user);

        resetService.reset(resetService.requestReset(email).orElseThrow(), NEW_PASSWORD);

        assertThat(reload().getTotpEnabledAt())
                .as("a password reset must not disarm two-step verification")
                .isNotNull();
    }

    @Test
    @DisplayName("the refusal for a spent link is distinguishable from a broken one")
    void spentAndInvalidDifferInStatus() {
        String token = resetService.requestReset(email).orElseThrow();
        resetService.reset(token, NEW_PASSWORD);

        // 409 for spent, 400 for never valid. The page uses this to decide whether
        // offering the form again would be worth the person's time.
        assertThatThrownBy(() -> resetService.reset(token, NEW_PASSWORD))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        assertThatThrownBy(() -> resetService.reset("nope", NEW_PASSWORD))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
