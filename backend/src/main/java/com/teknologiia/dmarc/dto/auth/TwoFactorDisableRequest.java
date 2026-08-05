package com.teknologiia.dmarc.dto.auth;

import jakarta.validation.constraints.NotBlank;

/** Turning the factor off needs the account password, not merely a live session. */
public record TwoFactorDisableRequest(@NotBlank String password) {}
