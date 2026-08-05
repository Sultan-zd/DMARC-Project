package com.teknologiia.dmarc.controller;

import com.teknologiia.dmarc.dto.user.UserCreateRequest;
import com.teknologiia.dmarc.dto.user.UserCreated;
import com.teknologiia.dmarc.dto.user.UserResponse;
import com.teknologiia.dmarc.security.AuthenticatedUser;
import com.teknologiia.dmarc.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Account management, scoped to the caller's organization.
 *
 * <p>Everything here sits under {@code /api/admin/**}, which SecurityConfig restricts
 * to the ADMIN role — so authorisation is enforced once, at the path, rather than
 * repeated in each method.
 */
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping
    public List<UserResponse> list(@AuthenticationPrincipal AuthenticatedUser caller) {
        return userService.getAllUsers(caller.getOrganizationId());
    }

    /**
     * Creates an account. Omitting the password asks for a generated one, which is
     * returned here and never again.
     */
    @PostMapping
    public UserCreated create(@AuthenticationPrincipal AuthenticatedUser caller,
                              @Valid @RequestBody UserCreateRequest request) {
        return userService.createUser(caller.getOrganizationId(), request);
    }

    @PatchMapping("/{id}/role")
    public UserResponse changeRole(@AuthenticationPrincipal AuthenticatedUser caller,
                                   @PathVariable Long id,
                                   @RequestBody Map<String, String> body) {
        return userService.changeRole(
                caller.getOrganizationId(), id, body.get("role"), caller.getUserId());
    }

    @PatchMapping("/{id}/active")
    public UserResponse setActive(@AuthenticationPrincipal AuthenticatedUser caller,
                                  @PathVariable Long id,
                                  @RequestBody Map<String, Boolean> body) {
        return userService.setActive(
                caller.getOrganizationId(), id, Boolean.TRUE.equals(body.get("active")), caller.getUserId());
    }

    /** Issues a new generated password, shown once. */
    @PostMapping("/{id}/reset-password")
    public Map<String, String> resetPassword(@AuthenticationPrincipal AuthenticatedUser caller,
                                             @PathVariable Long id) {
        return Map.of("password", userService.resetPassword(caller.getOrganizationId(), id));
    }

    @DeleteMapping("/{id}")
    public Map<String, Boolean> delete(@AuthenticationPrincipal AuthenticatedUser caller,
                                       @PathVariable Long id) {
        userService.deleteUser(caller.getOrganizationId(), id, caller.getUserId());
        return Map.of("deleted", true);
    }
}
