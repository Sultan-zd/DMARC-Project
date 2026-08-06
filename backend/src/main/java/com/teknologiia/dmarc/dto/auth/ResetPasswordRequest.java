package com.teknologiia.dmarc.dto.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * Redeems a reset link and sets the new password.
 *
 * <p>No current password: the person does not have it, which is the whole
 * situation. The token standing in its place arrived in their mailbox, and that is
 * the evidence being accepted.
 *
 * <p>Length is not checked here — {@code PasswordPolicy} is the single place that
 * decides what an acceptable password is, and a second opinion in a DTO annotation
 * is one that drifts.
 */
public record ResetPasswordRequest(
    @NotBlank String token,
    @NotBlank String password
) {}
