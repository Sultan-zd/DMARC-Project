package com.teknologiia.dmarc.service;

import com.teknologiia.dmarc.dto.auth.LoginRequest;
import com.teknologiia.dmarc.dto.auth.TokenResponse;
import com.teknologiia.dmarc.dto.user.UserResponse;
import com.teknologiia.dmarc.model.User;
import com.teknologiia.dmarc.repository.UserRepository;
import com.teknologiia.dmarc.security.JwtTokenProvider;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider tokenProvider;
    private final UserRepository userRepository;
    
    public AuthService(AuthenticationManager authenticationManager, JwtTokenProvider tokenProvider, UserRepository userRepository) {
        this.authenticationManager = authenticationManager;
        this.tokenProvider = tokenProvider;
        this.userRepository = userRepository;
    }

    public TokenResponse login(String username, String password) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(username, password)
        );
        
        User user = userRepository.findByUsername(username).orElseThrow();
        String token = tokenProvider.generateToken(username, user.getRole());
        
        return new TokenResponse(token, "Bearer", user.getRole(), username);
    }

    public TokenResponse refreshToken(String username) {
        User user = userRepository.findByUsername(username).orElseThrow();
        String token = tokenProvider.generateToken(username, user.getRole());
        return new TokenResponse(token, "Bearer", user.getRole(), username);
    }

    public UserResponse getCurrentUser(String username) {
        User user = userRepository.findByUsername(username).orElseThrow();
        return new UserResponse(user.getId(), username, user.getEmail(), user.getRole(), user.isActive(), user.getCreatedAt());
    }
}
