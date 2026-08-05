package com.teknologiia.dmarc.controller;

import com.teknologiia.dmarc.dto.auth.LoginRequest;
import com.teknologiia.dmarc.dto.auth.RegisterRequest;
import com.teknologiia.dmarc.dto.auth.TokenResponse;
import com.teknologiia.dmarc.dto.auth.TwoFactorChallengeRequest;
import com.teknologiia.dmarc.dto.auth.TwoFactorCodeRequest;
import com.teknologiia.dmarc.dto.auth.TwoFactorDisableRequest;
import com.teknologiia.dmarc.dto.auth.TwoFactorSetupResponse;
import com.teknologiia.dmarc.dto.auth.TwoFactorStatusResponse;
import com.teknologiia.dmarc.service.TwoFactorService;
import com.teknologiia.dmarc.dto.user.PasswordChangeRequest;
import com.teknologiia.dmarc.dto.user.UserResponse;
import com.teknologiia.dmarc.service.AuthService;
import com.teknologiia.dmarc.service.RegistrationService;
import com.teknologiia.dmarc.service.UserService;
import com.teknologiia.dmarc.web.ClientAddress;
import com.teknologiia.dmarc.web.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    /**
     * Sign-in allowance per address: a short burst, refilling slowly. Enough for
     * someone mistyping a password, far too little to guess one — the built-in
     * administrator username is public knowledge.
     */
    private static final int LOGIN_ATTEMPTS = 8;
    private static final double LOGIN_REFILL_PER_MINUTE = 2;

    /** Sign-up is cheaper to abuse than to serve, so it gets its own budget. */
    private static final int REGISTER_ATTEMPTS = 5;
    private static final double REGISTER_REFILL_PER_MINUTE = 1;

    private final AuthService authService;
    private final RegistrationService registrationService;
    private final UserService userService;
    private final RateLimiter rateLimiter;
    private final TwoFactorService twoFactorService;
    private final ClientAddress clientAddress;

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        // Keyed on address and username together, so one attacker hammering a single
        // account cannot also lock out everyone else arriving from that address.
        guard("login:" + clientIp(http) + ":" + request.username().toLowerCase(),
                LOGIN_ATTEMPTS, LOGIN_REFILL_PER_MINUTE,
                "Too many sign-in attempts. Try again in ");

        return authService.login(request.username(), request.password());
    }

    /**
     * Creates an organization and its first administrator.
     *
     * <p>Answers 202: the account exists but cannot be used until the emailed link
     * is followed. Until SMTP is configured the link is written to the server log.
     */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> register(@Valid @RequestBody RegisterRequest request,
                                        HttpServletRequest http) {
        guard("register:" + clientIp(http), REGISTER_ATTEMPTS, REGISTER_REFILL_PER_MINUTE,
                "Too many sign-up attempts. Try again in ");

        registrationService.register(request);
        return Map.of("detail",
                "Account created. Check your email to activate it before signing in.");
    }

    @GetMapping("/verify")
    public Map<String, String> verify(@RequestParam String token, HttpServletRequest http) {
        // Also bounded: the token is 256 bits, but there is no reason to allow
        // unlimited guesses at it.
        guard("verify:" + clientIp(http), REGISTER_ATTEMPTS, REGISTER_REFILL_PER_MINUTE,
                "Too many attempts. Try again in ");

        registrationService.verify(token);
        return Map.of("detail", "Your account is now active. You can sign in.");
    }

    /** Applies an allowance, translating exhaustion into 429 with a Retry-After. */
    private void guard(String key, int capacity, double refillPerMinute, String message) {
        RateLimiter.Decision decision = rateLimiter.tryAcquire(key, capacity, refillPerMinute);
        if (!decision.allowed()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    message + decision.retryAfterSeconds() + " seconds.");
        }
    }

    private String clientIp(HttpServletRequest request) {
        return clientAddress.of(request);
    }

    /**
     * Changes the caller's own password, clearing any forced-change flag.
     *
     * <p>Requires the current password: a stolen token alone must not be enough to
     * take over an account permanently.
     */
    @PostMapping("/change-password")
    public Map<String, String> changePassword(@AuthenticationPrincipal UserDetails userDetails,
                                              @Valid @RequestBody PasswordChangeRequest request) {
        userService.changeOwnPassword(
                userDetails.getUsername(), request.currentPassword(), request.newPassword());
        return Map.of("detail", "Your password has been changed.");
    }

    /**
     * Second stage of signing in, for accounts with a second factor.
     *
     * <p>Public, because the caller holds no session yet — only the challenge token
     * the first stage issued. Rate limited on the address alone: the username is
     * inside the challenge, and reading it here to key the limit would let anyone
     * probe which accounts have a factor at all.
     */
    @PostMapping("/login/2fa")
    public TokenResponse completeTwoFactor(@Valid @RequestBody TwoFactorChallengeRequest request,
                                           HttpServletRequest http) {
        guard("mfa:" + clientIp(http), LOGIN_ATTEMPTS, LOGIN_REFILL_PER_MINUTE,
                "Too many attempts. Try again in ");

        return authService.completeTwoFactor(request.mfaToken(), request.code());
    }

    // ─── Two-factor enrolment ───────────────────────────────────────

    @GetMapping("/2fa")
    public TwoFactorStatusResponse twoFactorStatus(@AuthenticationPrincipal UserDetails userDetails) {
        return twoFactorService.status(userDetails.getUsername());
    }

    /** Generates a secret and the QR code for it. Nothing is in force until confirmed. */
    @PostMapping("/2fa/setup")
    public TwoFactorSetupResponse beginTwoFactorSetup(@AuthenticationPrincipal UserDetails userDetails) {
        return twoFactorService.beginSetup(userDetails.getUsername());
    }

    /**
     * Confirms enrolment with a code from the app.
     *
     * <p>Returns the recovery codes, once. They are stored hashed, so this response
     * is the only chance anyone has to keep them.
     */
    @PostMapping("/2fa/enable")
    public Map<String, Object> enableTwoFactor(@AuthenticationPrincipal UserDetails userDetails,
                                               @Valid @RequestBody TwoFactorCodeRequest request) {
        return Map.of(
                "detail", "Two-factor authentication is on.",
                "recovery_codes", twoFactorService.enable(userDetails.getUsername(), request.code()));
    }

    @PostMapping("/2fa/disable")
    public Map<String, String> disableTwoFactor(@AuthenticationPrincipal UserDetails userDetails,
                                                @Valid @RequestBody TwoFactorDisableRequest request) {
        twoFactorService.disable(userDetails.getUsername(), request.password());
        return Map.of("detail", "Two-factor authentication is off.");
    }

    @PostMapping("/2fa/recovery-codes")
    public Map<String, Object> regenerateRecoveryCodes(@AuthenticationPrincipal UserDetails userDetails) {
        return Map.of(
                "detail", "New recovery codes issued. The previous ones no longer work.",
                "recovery_codes", twoFactorService.regenerateRecoveryCodes(userDetails.getUsername()));
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@AuthenticationPrincipal UserDetails userDetails) {
        return authService.refreshToken(userDetails.getUsername());
    }

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal UserDetails userDetails) {
        return authService.getCurrentUser(userDetails.getUsername());
    }
}
