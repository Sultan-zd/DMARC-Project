package com.teknologiia.dmarc.dto.invite;

/** What an invitee is shown before accepting: which team, and as what. */
public record InvitationPreview(
        String organization,
        String email,
        String role
) {}
