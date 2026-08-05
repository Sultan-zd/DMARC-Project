package com.teknologiia.dmarc.controller;

import com.teknologiia.dmarc.dto.invite.DomainClaimResponse;
import com.teknologiia.dmarc.dto.invite.InvitationResponse;
import com.teknologiia.dmarc.model.Invitation;
import com.teknologiia.dmarc.model.OrganizationDomain;
import com.teknologiia.dmarc.security.AuthenticatedUser;
import com.teknologiia.dmarc.service.InvitationService;
import com.teknologiia.dmarc.service.OrganizationDomainService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * Growing a team: invitations, and the email domains that let colleagues join by
 * signing up. Sits under /api/admin/**, so SecurityConfig restricts it to admins.
 */
@RestController
@RequestMapping("/api/admin/team")
@RequiredArgsConstructor
public class TeamController {

    private final InvitationService invitationService;
    private final OrganizationDomainService domainService;

    @Value("${app.public-url:http://localhost:5173}")
    private String publicUrl;

    // ── Invitations ────────────────────────────────────────────────

    @GetMapping("/invitations")
    public List<InvitationResponse> listInvitations(@AuthenticationPrincipal AuthenticatedUser caller) {
        return invitationService.list(caller.getOrganizationId()).stream()
                .map(invitation -> toResponse(invitation, null))
                .toList();
    }

    @PostMapping("/invitations")
    public InvitationResponse invite(@AuthenticationPrincipal AuthenticatedUser caller,
                                     @RequestBody Map<String, String> body) {
        Invitation invitation = invitationService.invite(
                caller.getOrganizationId(), body.get("email"), body.get("role"), caller.getUsername());

        // The link is returned once, so the admin can pass it on until mail is wired up.
        return toResponse(invitation, publicUrl + "/invitation?token=" + invitation.getToken());
    }

    @DeleteMapping("/invitations/{id}")
    public Map<String, Boolean> revoke(@AuthenticationPrincipal AuthenticatedUser caller,
                                       @PathVariable Long id) {
        invitationService.revoke(caller.getOrganizationId(), id);
        return Map.of("revoked", true);
    }

    // ── Claimed email domains ──────────────────────────────────────

    @GetMapping("/domains")
    public List<DomainClaimResponse> listDomains(@AuthenticationPrincipal AuthenticatedUser caller) {
        return domainService.list(caller.getOrganizationId()).stream()
                .map(TeamController::toResponse)
                .toList();
    }

    @PostMapping("/domains")
    public DomainClaimResponse claim(@AuthenticationPrincipal AuthenticatedUser caller,
                                     @RequestBody Map<String, String> body) {
        return toResponse(domainService.claim(
                caller.getOrganizationId(), body.get("domain"), body.get("defaultRole")));
    }

    @PostMapping("/domains/{id}/verify")
    public DomainClaimResponse verify(@AuthenticationPrincipal AuthenticatedUser caller,
                                      @PathVariable Long id) {
        return toResponse(domainService.verify(caller.getOrganizationId(), id));
    }

    @DeleteMapping("/domains/{id}")
    public Map<String, Boolean> release(@AuthenticationPrincipal AuthenticatedUser caller,
                                        @PathVariable Long id) {
        domainService.release(caller.getOrganizationId(), id);
        return Map.of("released", true);
    }

    // ── Mapping ────────────────────────────────────────────────────

    private static InvitationResponse toResponse(Invitation invitation, String link) {
        boolean pending = invitation.getAcceptedAt() == null
                && invitation.getExpiresAt().isAfter(LocalDateTime.now(ZoneOffset.UTC));

        return new InvitationResponse(
                invitation.getId(), invitation.getEmail(), invitation.getRole(),
                invitation.getInvitedBy(), invitation.getExpiresAt(),
                invitation.getAcceptedAt(), pending, link);
    }

    private static DomainClaimResponse toResponse(OrganizationDomain claim) {
        return new DomainClaimResponse(
                claim.getId(), claim.getDomain(), claim.isVerified(), claim.getVerifiedAt(),
                claim.getDefaultRole(), claim.verificationHost(), claim.getVerificationToken());
    }
}
