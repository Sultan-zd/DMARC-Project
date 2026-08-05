package com.teknologiia.dmarc.service;

import com.teknologiia.dmarc.dto.invite.AcceptInvitationRequest;
import com.teknologiia.dmarc.dto.invite.InvitationPreview;
import com.teknologiia.dmarc.model.Invitation;
import com.teknologiia.dmarc.model.Organization;
import com.teknologiia.dmarc.model.User;
import com.teknologiia.dmarc.repository.InvitationRepository;
import com.teknologiia.dmarc.repository.OrganizationRepository;
import com.teknologiia.dmarc.repository.UserRepository;
import com.teknologiia.dmarc.security.PasswordPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Inviting someone into an existing organization.
 *
 * <p>This is the deliberate path into a team. Without it, a colleague signing up on
 * their own creates a second organization with the same company name and an empty
 * dashboard — the two never meet.
 *
 * <p>The link is emailed to the invitee. When no mail host is configured it is
 * written to the log instead, so the flow works end to end without an SMTP server.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InvitationService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final InvitationRepository invitationRepository;
    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final OutboundMailService mailService;

    @Value("${app.invitation.ttl-hours:168}")
    private long ttlHours;

    @Value("${app.public-url:http://localhost:5173}")
    private String publicUrl;

    public List<Invitation> list(Long organizationId) {
        return invitationRepository.findByOrganizationIdOrderByCreatedAtDesc(organizationId);
    }

    @Transactional
    public Invitation invite(Long organizationId, String rawEmail, String rawRole, String invitedBy) {
        String email = rawEmail == null ? "" : rawEmail.trim().toLowerCase(Locale.ROOT);
        if (!email.contains("@")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a valid email address.");
        }

        String role = rawRole == null ? "VIEWER" : rawRole.trim().toUpperCase(Locale.ROOT);
        if (!UserService.ROLES.contains(role)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Role must be one of ADMIN, ANALYST or VIEWER.");
        }
        if (userRepository.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "An account already exists for that address.");
        }

        // Re-inviting replaces the outstanding link rather than leaving two valid ones.
        invitationRepository.findByEmailIgnoreCaseAndAcceptedAtIsNull(email)
                .ifPresent(invitationRepository::delete);

        Organization organization = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR, "Organization not found."));

        Invitation invitation = invitationRepository.save(Invitation.builder()
                .organization(organization)
                .email(email)
                .role(role)
                .token(randomToken())
                .invitedBy(invitedBy)
                .expiresAt(LocalDateTime.now(ZoneOffset.UTC).plus(Duration.ofHours(ttlHours)))
                .build());

        mailService.sendInvitation(email, organization.getName(), invitedBy, role,
                publicUrl + "/invitation?token=" + invitation.getToken());

        return invitation;
    }

    @Transactional
    public void revoke(Long organizationId, Long invitationId) {
        Invitation invitation = invitationRepository
                .findByIdAndOrganizationId(invitationId, organizationId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No such invitation."));
        invitationRepository.delete(invitation);
    }

    /** What the invitee sees before deciding: which organization, and as what. */
    public InvitationPreview preview(String token) {
        Invitation invitation = usable(token);
        return new InvitationPreview(
                invitation.getOrganization().getName(),
                invitation.getEmail(),
                invitation.getRole());
    }

    /**
     * Creates the account inside the inviting organization.
     *
     * <p>Active immediately: receiving the link already proves control of the
     * address, so a second email confirmation would add nothing.
     */
    @Transactional
    public String accept(String token, AcceptInvitationRequest request) {
        Invitation invitation = usable(token);
        String username = request.username().trim();

        if (userRepository.existsByUsername(username)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That username is already taken.");
        }
        if (userRepository.existsByEmail(invitation.getEmail())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "An account already exists for that address.");
        }
        passwordPolicy.enforce(request.password(), username, invitation.getEmail());

        userRepository.save(User.builder()
                .organization(invitation.getOrganization())
                .username(username)
                .email(invitation.getEmail())
                .hashedPassword(passwordEncoder.encode(request.password()))
                .role(invitation.getRole())
                .active(true)
                // The invitee chose this password themselves, so nothing to force.
                .mustChangePassword(false)
                .build());

        invitation.setAcceptedAt(LocalDateTime.now(ZoneOffset.UTC));
        invitationRepository.save(invitation);

        log.info("{} joined organization '{}' as {}",
                username, invitation.getOrganization().getName(), invitation.getRole());
        return invitation.getOrganization().getName();
    }

    private Invitation usable(String token) {
        Invitation invitation = invitationRepository.findByToken(token)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "This invitation link is not valid."));

        if (!invitation.isUsable()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    invitation.getAcceptedAt() != null
                            ? "This invitation has already been used."
                            : "This invitation has expired. Ask for a new one.");
        }
        return invitation;
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
