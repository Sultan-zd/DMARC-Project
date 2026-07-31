package com.teknologiia.dmarc.dto.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record UserCreateRequest(
    @NotBlank String username,
    @Email String email,
    @NotBlank String password,
    String role
) {}
