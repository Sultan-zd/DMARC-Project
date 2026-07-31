package com.teknologiia.dmarc.service;

import com.teknologiia.dmarc.dto.user.UserCreateRequest;
import com.teknologiia.dmarc.dto.user.UserResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class UserService {
    private final PasswordEncoder passwordEncoder;

    public UserService(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
    }

    public List<UserResponse> getAllUsers() {
        return List.of();
    }

    public UserResponse createUser(UserCreateRequest request) {
        String encoded = passwordEncoder.encode(request.password());
        return new UserResponse(1L, request.username(), request.email(), request.role(), true, null);
    }

    public UserResponse findByUsername(String username) {
        return new UserResponse(1L, username, "email@example.com", "USER", true, null);
    }
}
