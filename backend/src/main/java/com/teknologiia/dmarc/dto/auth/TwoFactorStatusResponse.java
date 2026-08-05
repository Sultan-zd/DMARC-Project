package com.teknologiia.dmarc.dto.auth;

import java.time.LocalDateTime;

/**
 * @param recoveryCodesLeft how many unused recovery codes remain — shown so nobody
 *                          discovers the answer is none at the moment they need one
 */
public record TwoFactorStatusResponse(
        boolean enabled,
        LocalDateTime enabledAt,
        long recoveryCodesLeft
) {}
