package com.teknologiia.dmarc.dto.user;

import java.time.LocalDateTime;

public record UserResponse(
    Long id,
    String username,
    String email,
    String role,
    Boolean is_active,
    LocalDateTime created_at
) {}
