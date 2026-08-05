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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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

        user.setRole(role);
        return toResponse(userRepository.save(user));
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
        return toResponse(userRepository.save(user));
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

        log.info("Password reset for '{}' in organization {}", user.getUsername(), organizationId);
        return password;
    }

    /** A user changing their own password, which also clears the forced-change flag. */
    @Transactional
    public void changeOwnPassword(String username, String currentPassword, String newPassword) {
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
