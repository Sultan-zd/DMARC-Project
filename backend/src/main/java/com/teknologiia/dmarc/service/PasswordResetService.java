package com.teknologiia.dmarc.service;

import com.teknologiia.dmarc.model.PasswordResetToken;
import com.teknologiia.dmarc.model.User;
import com.teknologiia.dmarc.repository.PasswordResetTokenRepository;
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
import java.util.List;
import java.util.Optional;

/**
 * Getting back in after forgetting a password.
 *
 * <p>Before this existed the only way back was another administrator resetting the
 * password by hand — and in an organization with one administrator, who was also
 * the person locked out, there was no way back at all.
 *
 * <p>The flow proves the same thing sign-up proves: that the person asking controls
 * the mailbox the account was registered with. Nothing else is accepted as
 * evidence, which is why the request needs no session and the reset needs no old
 * password.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PasswordResetService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final OutboundMailService mailService;

    /**
     * Shorter than the 24 hours a sign-up link gets. A confirmation link sitting
     * unread in an inbox costs nothing; a link that rewrites a password is worth an
     * hour and no more.
     */
    @Value("${app.password-reset.token-ttl-minutes:60}")
    private long tokenTtlMinutes;

    /**
     * Sends a reset link, if that address belongs to an account.
     *
     * <p><strong>Says nothing either way.</strong> The caller gets the same answer
     * whether or not the address is registered, because the endpoint is public and
     * anything that distinguished the two cases would turn it into a way of asking
     * "does this person have an account here" — for any address, without a session.
     * For a security product whose customers are named on their own dashboards,
     * that list is worth having.
     *
     * <p>Which is also why nothing here throws. A missing account is a normal
     * outcome, not an error.
     *
     * @return the token when one was issued, for tests and for the development flow
     *         where no SMTP server exists. Never returned to a caller over HTTP.
     */
    @Transactional
    public Optional<String> requestReset(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }

        Optional<User> found = userRepository.findByEmailIgnoreCase(email.trim());
        if (found.isEmpty()) {
            // Logged at debug: a stream of these at info level would itself be the
            // list of addresses that are not customers.
            log.debug("Password reset asked for an address with no account");
            return Optional.empty();
        }

        User user = found.get();

        // Asking for a new link is how somebody reacts to losing the device the old
        // one is sitting on. It has to actually take the old one away.
        spendOutstanding(user, "superseded by a newer request");

        String token = newToken();
        tokenRepository.save(PasswordResetToken.builder()
                .token(token)
                .user(user)
                .expiresAt(LocalDateTime.now(ZoneOffset.UTC).plus(Duration.ofMinutes(tokenTtlMinutes)))
                .build());

        mailService.sendPasswordReset(user.getEmail(), user.getUsername(), token, tokenTtlMinutes);
        log.info("Password reset link issued for {}", user.getUsername());

        return Optional.of(token);
    }

    /**
     * Redeems a reset token and sets the new password.
     *
     * <p>No current password is asked for — the person does not have it, that is the
     * situation. What stands in for it is control of the mailbox, which the token
     * having arrived there demonstrates.
     */
    @Transactional
    public void reset(String token, String newPassword) {
        PasswordResetToken record = tokenRepository.findByToken(token)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "This reset link is not valid."));

        if (record.getUsedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This reset link has already been used. Ask for a new one if you still "
                            + "need to change your password.");
        }
        if (!record.isUsable()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "This reset link has expired. Ask for a new one.");
        }

        User user = record.getUser();

        // The same rule sign-up and self-service changes are held to, so a password
        // arrived at this way is not weaker than one chosen any other way.
        passwordPolicy.enforce(newPassword, user.getUsername(), user.getEmail());

        user.setHashedPassword(passwordEncoder.encode(newPassword));

        // They have just chosen their own, so the flag that holds an account at the
        // change screen has been satisfied.
        user.setMustChangePassword(false);

        // A sign-up that was never confirmed leaves an account nobody can enable:
        // the confirmation link expires and there is no way to ask for another. But
        // completing this flow proves control of the very mailbox that confirmation
        // was waiting on — the same evidence, arriving by a different route. So it
        // counts, and the account stops being stranded.
        if (!user.isActive()) {
            user.setActive(true);
            log.info("Account {} activated by completing a password reset", user.getUsername());
        }

        userRepository.save(user);

        record.setUsedAt(LocalDateTime.now(ZoneOffset.UTC));
        tokenRepository.save(record);

        // Any other link that was outstanding dies with this one. Somebody who has
        // just recovered an account should not leave a second key under the mat.
        spendOutstanding(user, "password already reset");

        log.info("Password reset completed for {}", user.getUsername());
    }

    /**
     * Marks every unused token for this account as spent.
     *
     * <p>Recorded as used rather than deleted: the row is the evidence that a link
     * existed and was invalidated, which is worth more when someone later asks what
     * happened to an account than a row that quietly disappeared.
     */
    private void spendOutstanding(User user, String reason) {
        List<PasswordResetToken> outstanding = tokenRepository.findByUserAndUsedAtIsNull(user);
        if (outstanding.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        outstanding.forEach(t -> t.setUsedAt(now));
        tokenRepository.saveAll(outstanding);
        log.info("Invalidated {} outstanding reset link(s) for {} — {}",
                outstanding.size(), user.getUsername(), reason);
    }

    /** 256 bits, URL-safe: it travels in a query string. */
    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
