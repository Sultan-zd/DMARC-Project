package com.teknologiia.dmarc.dto.auth;

import jakarta.validation.constraints.NotBlank;

/** A code from the authenticator app, or a recovery code. */
public record TwoFactorCodeRequest(@NotBlank String code) {}
