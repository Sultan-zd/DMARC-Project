package com.teknologiia.dmarc.dto.invite;

import java.time.LocalDateTime;

public record InvitationResponse(
        Long id,
        String email,
        String role,
        String invited_by,
        LocalDateTime expires_at,
        LocalDateTime accepted_at,
        Boolean pending,
        /** Present only just after creation, so the admin can pass the link on. */
        String link
) {}
