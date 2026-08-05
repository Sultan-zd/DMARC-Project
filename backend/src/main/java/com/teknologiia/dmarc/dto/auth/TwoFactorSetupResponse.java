package com.teknologiia.dmarc.dto.auth;

/**
 * What somebody needs to enrol an authenticator app.
 *
 * @param secret       the shared secret, for entering by hand when a camera is not available
 * @param otpauthUri   the URI an app scans
 * @param qrDataUri    that URI as an inline PNG, or null if it could not be rendered
 */
public record TwoFactorSetupResponse(String secret, String otpauthUri, String qrDataUri) {}
