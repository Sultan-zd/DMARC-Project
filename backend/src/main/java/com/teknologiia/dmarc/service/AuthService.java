package com.teknologiia.dmarc.service;

import com.teknologiia.dmarc.dto.auth.LoginRequest;
import com.teknologiia.dmarc.dto.auth.TokenResponse;
import com.teknologiia.dmarc.dto.user.UserResponse;
import com.teknologiia.dmarc.model.User;
import com.teknologiia.dmarc.repository.UserRepository;
import com.teknologiia.dmarc.security.JwtTokenProvider;
import com.teknologiia.dmarc.security.PlatformAccess;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider tokenProvider;
    private final UserRepository userRepository;
    private final TwoFactorService twoFactorService;
    private final PlatformAccess platformAccess;

    public AuthService(AuthenticationManager authenticationManager, JwtTokenProvider tokenProvider,
                       UserRepository userRepository, TwoFactorService twoFactorService,
                       PlatformAccess platformAccess) {
        this.authenticationManager = authenticationManager;
        this.tokenProvider = tokenProvider;
        this.userRepository = userRepository;
        this.twoFactorService = twoFactorService;
        this.platformAccess = platformAccess;
    }

    public TokenResponse login(String username, String password) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(username, password));
        } catch (DisabledException e) {
            // An unverified sign-up is stored disabled, so this is the normal outcome
            // of trying to sign in before following the confirmation link. Say so,
            // rather than reporting bad credentials.
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "This account is not activated yet. Check your email for the verification link.");
        }

        User user = userRepository.findByUsername(username).orElseThrow();

        // The password was right, but on an account with a second factor that is only
        // the first half of signing in. No session is issued here.
        if (user.getTotpEnabledAt() != null) {
            return TokenResponse.challenge(tokenProvider.generateMfaChallengeToken(username), username);
        }

        return session(user);
    }

    /**
     * Second stage: exchanges a challenge token and a code for a session.
     *
     * <p>Every failure says the same thing. Distinguishing "that challenge expired"
     * from "that code is wrong" tells an attacker which half they have already
     * solved.
     */
    public TokenResponse completeTwoFactor(String mfaToken, String code) {
        String username = tokenProvider.getUsernameFromChallenge(mfaToken);
        if (username == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "That sign-in could not be completed. Start again.");
        }

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "That sign-in could not be completed. Start again."));

        if (user.getTotpEnabledAt() == null || !twoFactorService.verifySecondFactor(user, code)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "That code is not right. Check your authenticator app and try the current one.");
        }

        return session(user);
    }

    private TokenResponse session(User user) {
        return TokenResponse.session(
                tokenProvider.generateToken(user.getUsername(), user.getRole()),
                user.getRole(), user.getUsername(), user.isMustChangePassword());
    }

    public TokenResponse refreshToken(String username) {
        // Reached only through an existing session, so the second factor is behind us.
        return session(userRepository.findByUsername(username).orElseThrow());
    }

    public UserResponse getCurrentUser(String username) {
        User user = userRepository.findByUsername(username).orElseThrow();
        return new UserResponse(user.getId(), username, user.getEmail(), user.getRole(),
                user.isActive(), user.getCreatedAt(),
                user.getOrganization() == null ? null : user.getOrganization().getName(),
                user.getTotpEnabledAt() != null,
                platformAccess.isOperator(username));
    }
}
