package com.teknologiia.dmarc.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Sign-up payload. Creates an organization and its first administrator together —
 * an account cannot exist outside a tenant.
 */
public record RegisterRequest(

        @NotBlank(message = "Organization name is required.")
        @Size(min = 2, max = 120, message = "Organization name must be 2–120 characters.")
        String organization,

        @NotBlank(message = "Username is required.")
        @Size(min = 3, max = 50, message = "Username must be 3–50 characters.")
        @Pattern(regexp = "^[a-zA-Z0-9._-]+$",
                message = "Username may only contain letters, digits, dot, underscore and hyphen.")
        String username,

        @NotBlank(message = "Email is required.")
        @Email(message = "Enter a valid email address.")
        @Size(max = 255)
        String email,

        @NotBlank(message = "Password is required.")
        @Size(min = 10, max = 200, message = "Password must be at least 10 characters.")
        String password
) {}
