package com.teknologiia.dmarc.dto.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * @param password leave blank to have a strong one generated and shown once
 */
public record UserCreateRequest(
    @NotBlank(message = "Enter a username.")
    @Size(min = 3, max = 50, message = "Username must be 3–50 characters.")
    @Pattern(regexp = "^[a-zA-Z0-9._-]+$",
            message = "Username may only contain letters, digits, dot, underscore and hyphen.")
    String username,

    @Email(message = "Enter a valid email address.")
    @Size(max = 255)
    String email,

    String password,

    String role
) {}
