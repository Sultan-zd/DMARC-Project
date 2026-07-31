package com.teknologiia.dmarc.dto.auth;

public record TokenResponse(
    String access_token,
    String token_type,
    String role,
    String username
) {}
