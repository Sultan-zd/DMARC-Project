package com.teknologiia.dmarc.dto.invite;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** The email is not asked for: it comes from the invitation, which is what proves it. */
public record AcceptInvitationRequest(
        @NotBlank(message = "Choose a username.")
        @Size(min = 3, max = 50, message = "Username must be 3–50 characters.")
        @Pattern(regexp = "^[a-zA-Z0-9._-]+$",
                message = "Username may only contain letters, digits, dot, underscore and hyphen.")
        String username,

        @NotBlank(message = "Choose a password.")
        String password
) {}
