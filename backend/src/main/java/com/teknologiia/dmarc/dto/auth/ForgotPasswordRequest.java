package com.teknologiia.dmarc.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Asks for a reset link.
 *
 * <p>Addressed by mailbox rather than by username: somebody who has forgotten
 * their password has often also forgotten which spelling of their name they signed
 * up with, but not their own email address.
 */
public record ForgotPasswordRequest(
    @NotBlank @Email String email
) {}
