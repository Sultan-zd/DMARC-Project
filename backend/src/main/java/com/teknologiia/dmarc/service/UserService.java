package com.teknologiia.dmarc.service;

import com.teknologiia.dmarc.dto.user.UserCreateRequest;
import com.teknologiia.dmarc.dto.user.UserCreated;
import com.teknologiia.dmarc.dto.user.UserResponse;
import com.teknologiia.dmarc.model.Organization;
import com.teknologiia.dmarc.model.User;
import com.teknologiia.dmarc.repository.OrganizationRepository;
import com.teknologiia.dmarc.repository.UserRepository;
import com.teknologiia.dmarc.security.PasswordPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Account management within one organization.
 *
 * <p>Every operation is scoped to the caller's organization: an administrator
 * manages their own team and cannot see, change or remove anyone else's accounts.
 *
 * <p>This class previously returned fabricated data — {@code getAllUsers} answered
 * an empty list and {@code createUser} hashed the password and discarded it, so the
 * admin screen reported success while nothing was stored.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    /** The roles the product actually enforces. */
    public static final Set<String> ROLES = Set.of("ADMIN", "ANALYST", "VIEWER");

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final AuditService auditService;
    private final SessionService sessionService;

    /**
     * Who is acting, for the audit trail.
     *
     * <p>Read from the security context rather than taken as a parameter: every
     * caller of these methods is a signed-in administrator, and threading the name
     * through four signatures that already carry a caller id would be the same
     * value twice. Falls back to {@code system} for anything running outside a
     * request, where nobody asked.
     */
    private static String callerUsername() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? AuditService.SYSTEM : authentication.getName();
    }

    public List<UserResponse> getAllUsers(Long organizationId) {
        return userRepository.findByOrganizationIdOrderByCreatedAtAsc(organizationId).stream()
                .map(UserService::toResponse)
                .toList();
    }

    /**
     * Creates an account in the caller's organization.
     *
     * @return the account, plus the generated password when one was produced —
     *         it is shown once and never recoverable afterwards
     */
    @Transactional
    public UserCreated createUser(Long organizationId, UserCreateRequest request) {
        String username = request.username().trim();
        String email = request.email() == null ? null : request.email().trim().toLowerCase(Locale.ROOT);
        String role = normaliseRole(request.role());

        if (userRepository.existsByUsername(username)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That username is already taken.");
        }
        if (email != null && userRepository.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That email address is already registered.");
        }

        // Either the administrator supplied a password, which must clear the policy,
        // or they asked for one to be generated.
        boolean generated = request.password() == null || request.password().isBlank();
        String password = generated ? passwordPolicy.generate() : request.password();
        if (!generated) {
            passwordPolicy.enforce(password, username, email);
        }

        Organization organization = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR, "Organization not found."));

        User saved = userRepository.save(User.builder()
                .organization(organization)
                .username(username)
                .email(email)
                .hashedPassword(passwordEncoder.encode(password))
                .role(role)
                .active(true)
                // Whoever set this password is not the person who will use it.
                .mustChangePassword(true)
                .build());

        // The password is deliberately not in the detail. An audit trail that
        // records credentials is a credential store nobody thinks to protect.
        auditService.record(callerUsername(), organizationId, AuditAction.ACCOUNT_CREATED,
                AuditAction.TARGET_ACCOUNT, saved.getId(), saved.getUsername(),
                role + " · " + email);

        log.info("Created {} account '{}' in organization {}", role, username, organizationId);
        return new UserCreated(toResponse(saved), generated ? password : null);
    }

    /** Changes a member's role, refusing to leave the organization without an admin. */
    @Transactional
    public UserResponse changeRole(Long organizationId, Long userId, String newRole, Long callerId) {
        User user = requireUser(organizationId, userId);
        String role = normaliseRole(newRole);

        if ("ADMIN".equals(user.getRole()) && !"ADMIN".equals(role)) {
            requireAnotherAdmin(organizationId, "You cannot remove the last administrator.");
        }
        if (userId.equals(callerId) && !"ADMIN".equals(role)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "You cannot remove your own administrator access.");
        }

        String previous = user.getRole();
        user.setRole(role);
        UserResponse saved = toResponse(userRepository.save(user));

        // Authorities are read from the database on every request, so the change
        // takes effect at once -- no revocation needed, only a record of it.
        auditService.record(callerUsername(), organizationId, AuditAction.ACCOUNT_ROLE_CHANGED,
                AuditAction.TARGET_ACCOUNT, user.getId(), user.getUsername(),
                previous + " to " + role);
        return saved;
    }

    /** Enables or disables an account. Disabled accounts cannot authenticate. */
    @Transactional
    public UserResponse setActive(Long organizationId, Long userId, boolean active, Long callerId) {
        if (userId.equals(callerId) && !active) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "You cannot deactivate your own account.");
        }

        User user = requireUser(organizationId, userId);
        if (!active && "ADMIN".equals(user.getRole())) {
            requireAnotherAdmin(organizationId, "You cannot deactivate the last administrator.");
        }

        user.setActive(active);
        UserResponse saved = toResponse(userRepository.save(user));

        // Disabling has to reach the sessions already open. Spring refuses a
        // disabled account at sign-in, but a token issued before it was disabled
        // arrives at the filter instead -- so the account has to be cut off
        // explicitly, or being disabled means nothing for the next hour.
        if (!active) {
            sessionService.revokeAll(user, callerUsername(), "account disabled");
        }
        auditService.record(callerUsername(), organizationId,
                active ? AuditAction.ACCOUNT_ENABLED : AuditAction.ACCOUNT_DISABLED,
                AuditAction.TARGET_ACCOUNT, user.getId(), user.getUsername(), null);
        return saved;
    }

    @Transactional
    public void deleteUser(Long organizationId, Long userId, Long callerId) {
        if (userId.equals(callerId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "You cannot delete your own account.");
        }

        User user = requireUser(organizationId, userId);
        if ("ADMIN".equals(user.getRole())) {
            requireAnotherAdmin(organizationId, "You cannot delete the last administrator.");
        }

        // Recorded before the delete, while the account still has a name: an entry
        // reading "account #47" answers nothing once #47 is gone.
        auditService.record(callerUsername(), organizationId, AuditAction.ACCOUNT_DELETED,
                AuditAction.TARGET_ACCOUNT, user.getId(), user.getUsername(),
                user.getEmail() + " · " + user.getRole());

        userRepository.delete(user);
        log.info("Deleted account '{}' from organization {}", user.getUsername(), organizationId);
    }

    /** Resets a member's password to a freshly generated one, shown once. */
    @Transactional
    public String resetPassword(Long organizationId, Long userId) {
        User user = requireUser(organizationId, userId);
        String password = passwordPolicy.generate();

        user.setHashedPassword(passwordEncoder.encode(password));
        user.setMustChangePassword(true);
        userRepository.save(user);

        // Whoever held a session on this account is not necessarily its owner --
        // that is often why the password is being reset.
        sessionService.revokeAll(user, callerUsername(), "password reset by an administrator");
        auditService.record(callerUsername(), organizationId,
                AuditAction.ACCOUNT_PASSWORD_RESET_BY_ADMIN, AuditAction.TARGET_ACCOUNT,
                user.getId(), user.getUsername(), null);

        log.info("Password reset for '{}' in organization {}", user.getUsername(), organizationId);
        return password;
    }

    /**
     * A user changing their own password, which also clears the forced-change flag.
     *
     * @return the instant every session on this account was invalidated, so the
     *         caller can mint a replacement dated past it and keep this one alive
     */
    @Transactional
    public LocalDateTime changeOwnPassword(String username, String currentPassword, String newPassword) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found."));

        if (!passwordEncoder.matches(currentPassword, user.getHashedPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Your current password is not correct.");
        }
        if (passwordEncoder.matches(newPassword, user.getHashedPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The new password must be different from the current one.");
        }
        passwordPolicy.enforce(newPassword, user.getUsername(), user.getEmail());

        user.setHashedPassword(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        userRepository.save(user);

        // Changing a password because it may have been seen is only half done if
        // the sessions opened with the old one keep working. This ends all of them,
        // including the one making the request — the caller mints a replacement
        // token afterwards so the person doing the right thing is not punished for
        // it by being thrown out.
        LocalDateTime revokedAt =
                sessionService.revokeAll(user, username, "password changed by its owner");
        auditService.record(username, user.getOrganization().getId(),
                AuditAction.PASSWORD_CHANGED, AuditAction.TARGET_ACCOUNT,
                user.getId(), user.getUsername(), null);
        return revokedAt;
    }

    public UserResponse findByUsername(String username) {
        return userRepository.findByUsername(username)
                .map(UserService::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found."));
    }

    // ─── Helpers ────────────────────────────────────────────────────

    private User requireUser(Long organizationId, Long userId) {
        return userRepository.findByIdAndOrganizationId(userId, organizationId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No such account in this organization."));
    }

    /** An organization with no active administrator can never be managed again. */
    private void requireAnotherAdmin(Long organizationId, String message) {
        if (userRepository.countByOrganizationIdAndRoleIgnoreCaseAndActiveTrue(organizationId, "ADMIN") <= 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
    }

    private static String normaliseRole(String role) {
        String value = role == null ? "VIEWER" : role.trim().toUpperCase(Locale.ROOT);
        if (!ROLES.contains(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Role must be one of ADMIN, ANALYST or VIEWER.");
        }
        return value;
    }

    private static UserResponse toResponse(User user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getEmail(),
                user.getRole(), user.isActive(), user.getCreatedAt(),
                user.getOrganization() == null ? null : user.getOrganization().getName(),
                user.getTotpEnabledAt() != null,
                // Operator status is a property of the deployment, not of an
                // account, and is reported only by /auth/me for the caller.
                false);
    }
}
