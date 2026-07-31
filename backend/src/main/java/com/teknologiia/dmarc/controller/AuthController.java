package com.teknologiia.dmarc.controller;

import com.teknologiia.dmarc.dto.auth.LoginRequest;
import com.teknologiia.dmarc.dto.auth.TokenResponse;
import com.teknologiia.dmarc.dto.user.UserResponse;
import com.teknologiia.dmarc.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request.username(), request.password());
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@AuthenticationPrincipal UserDetails userDetails) {
        return authService.refreshToken(userDetails.getUsername());
    }

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal UserDetails userDetails) {
        return authService.getCurrentUser(userDetails.getUsername());
    }
}
