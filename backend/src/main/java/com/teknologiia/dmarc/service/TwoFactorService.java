package com.teknologiia.dmarc.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.teknologiia.dmarc.dto.auth.TwoFactorSetupResponse;
import com.teknologiia.dmarc.dto.auth.TwoFactorStatusResponse;
import com.teknologiia.dmarc.model.RecoveryCode;
import com.teknologiia.dmarc.model.User;
import com.teknologiia.dmarc.repository.RecoveryCodeRepository;
import com.teknologiia.dmarc.repository.UserRepository;
import com.teknologiia.dmarc.security.TotpService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Enrolling, confirming and using a second factor.
 *
 * <p>The order matters. A secret is generated when enrolment starts but does not
 * take effect until the person proves they can read a code from it — otherwise a
 * mistyped setup, or one abandoned halfway, would lock the account out of its own
 * sign-in with no way back.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TwoFactorService {

    /** How many recovery codes are issued. Ten is what the major providers give. */
    private static final int RECOVERY_CODES = 10;

    /** Unambiguous alphabet: no O/0, no I/1/l, since these get read off paper. */
    private static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    private static final int QR_PIXELS = 240;

    private final UserRepository userRepository;
    private final RecoveryCodeRepository recoveryCodeRepository;
    private final TotpService totpService;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom random = new SecureRandom();

    @Value("${app.mail.from-name:Teknologiia DMARC}")
    private String issuer;

    public TwoFactorStatusResponse status(String username) {
        User user = require(username);
        return new TwoFactorStatusResponse(
                user.getTotpEnabledAt() != null,
                user.getTotpEnabledAt(),
                recoveryCodeRepository.countByUserIdAndUsedAtIsNull(user.getId()));
    }

    /**
     * Starts enrolment: a new secret, the URI to scan, and that URI as a QR image.
     *
     * <p>Calling it again replaces any unconfirmed secret, so an abandoned attempt
     * never leaves a stale one behind. It refuses once the factor is live — changing
     * the secret then would silently invalidate the app already set up.
     */
    @Transactional
    public TwoFactorSetupResponse beginSetup(String username) {
        User user = require(username);
        if (user.getTotpEnabledAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Two-factor authentication is already on. Turn it off first to enrol a new device.");
        }

        String secret = totpService.generateSecret();
        user.setTotpSecret(secret);
        userRepository.save(user);

        String account = user.getEmail() != null && !user.getEmail().isBlank()
                ? user.getEmail() : user.getUsername();
        String uri = totpService.otpauthUri(secret, account, issuer);

        return new TwoFactorSetupResponse(secret, uri, qrDataUri(uri));
    }

    /**
     * Confirms enrolment with a code from the app, and issues the recovery codes.
     *
     * <p>The plaintext codes are returned exactly once. They are stored hashed, so
     * this response is the only opportunity anyone has to write them down.
     */
    @Transactional
    public List<String> enable(String username, String code) {
        User user = require(username);
        if (user.getTotpEnabledAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Two-factor authentication is already on.");
        }
        if (user.getTotpSecret() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Start the setup first, then enter a code from your authenticator app.");
        }
        if (!totpService.verify(user.getTotpSecret(), code)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That code is not right. Check your authenticator app and try the current one.");
        }

        user.setTotpEnabledAt(LocalDateTime.now(ZoneOffset.UTC));
        userRepository.save(user);

        List<String> plain = issueRecoveryCodes(user);
        log.info("Two-factor authentication enabled for {}", username);
        return plain;
    }

    /**
     * Turns the second factor off.
     *
     * <p>Requires the account password. Without that, anyone who walks up to an
     * unlocked screen can remove the protection the factor exists to provide.
     */
    @Transactional
    public void disable(String username, String password) {
        User user = require(username);
        if (!passwordEncoder.matches(password, user.getHashedPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That password is not correct.");
        }

        user.setTotpSecret(null);
        user.setTotpEnabledAt(null);
        userRepository.save(user);
        recoveryCodeRepository.deleteByUserId(user.getId());
        log.info("Two-factor authentication disabled for {}", username);
    }

    /** Replaces the recovery codes, invalidating any still unused. */
    @Transactional
    public List<String> regenerateRecoveryCodes(String username) {
        User user = require(username);
        if (user.getTotpEnabledAt() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Two-factor authentication is not on for this account.");
        }
        return issueRecoveryCodes(user);
    }

    /**
     * Whether this second factor answers, by app code or by recovery code.
     *
     * <p>A recovery code is spent on use. Reusing one would make it a second
     * password rather than a way back in.
     */
    @Transactional
    public boolean verifySecondFactor(User user, String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        if (totpService.verify(user.getTotpSecret(), code)) {
            return true;
        }

        String candidate = code.trim().toUpperCase(Locale.ROOT).replace("-", "");
        for (RecoveryCode stored : recoveryCodeRepository.findByUserId(user.getId())) {
            if (!stored.isSpent() && passwordEncoder.matches(candidate, stored.getCodeHash())) {
                stored.setUsedAt(LocalDateTime.now(ZoneOffset.UTC));
                recoveryCodeRepository.save(stored);
                log.warn("{} signed in with a recovery code; {} remain", user.getUsername(),
                        recoveryCodeRepository.countByUserIdAndUsedAtIsNull(user.getId()));
                return true;
            }
        }
        return false;
    }

    private List<String> issueRecoveryCodes(User user) {
        recoveryCodeRepository.deleteByUserId(user.getId());

        List<String> plain = new ArrayList<>(RECOVERY_CODES);
        for (int i = 0; i < RECOVERY_CODES; i++) {
            String code = randomCode();
            plain.add(code.substring(0, 5) + "-" + code.substring(5));
            recoveryCodeRepository.save(RecoveryCode.builder()
                    .user(user)
                    // Hashed with the same encoder as passwords: a code that opens an
                    // account deserves the same treatment as the password it bypasses.
                    .codeHash(passwordEncoder.encode(code))
                    .build());
        }
        return plain;
    }

    private String randomCode() {
        StringBuilder out = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            out.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        }
        return out.toString();
    }

    /** The enrolment URI as an inline PNG, so the page loads nothing external. */
    private String qrDataUri(String uri) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(uri, BarcodeFormat.QR_CODE,
                    QR_PIXELS, QR_PIXELS,
                    Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                            EncodeHintType.MARGIN, 1));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (com.google.zxing.WriterException | IOException e) {
            // The secret is also returned as text, so enrolment still works by hand.
            log.warn("Could not render the enrolment QR code: {}", e.getMessage());
            return null;
        }
    }

    private User require(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such account."));
    }
}
