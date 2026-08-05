package com.teknologiia.dmarc.dto.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * Second stage of signing in.
 *
 * @param mfaToken short-lived proof that the password was already accepted
 * @param code     the six digits from the app, or a recovery code
 */
public record TwoFactorChallengeRequest(@NotBlank String mfaToken, @NotBlank String code) {}
