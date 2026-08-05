package com.teknologiia.dmarc.dto.user;

import java.time.LocalDateTime;

/**
 * @param organization        the team the account belongs to. Settings shows it so a
 *                            member can tell which organization they landed in — the
 *                            question that arises the moment two people at the same
 *                            company sign up separately.
 * @param two_factor_enabled  whether this account carries a second factor. An
 *                            administrator can otherwise only find out by asking
 *                            everyone, which is how it stays off.
 * @param platform_operator   whether this account runs the service itself, as
 *                            opposed to one organization within it. Comes from
 *                            deployment configuration, never from stored data.
 */
public record UserResponse(
    Long id,
    String username,
    String email,
    String role,
    Boolean is_active,
    LocalDateTime created_at,
    String organization,
    boolean two_factor_enabled,
    boolean platform_operator
) {}
