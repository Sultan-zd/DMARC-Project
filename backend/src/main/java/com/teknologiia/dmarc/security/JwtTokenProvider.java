package com.teknologiia.dmarc.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;

/**
 * Issues and verifies the session tokens.
 *
 * <p>The signing key is the single most sensitive value in the application: anyone
 * holding it can mint a valid token for any user, including an administrator,
 * without ever knowing a password. It is therefore never stored in the tracked
 * configuration — it comes from the environment, and when absent a throwaway key is
 * generated so development works without shipping a usable secret in the repository.
 */
@Component
@Slf4j
public class JwtTokenProvider {

    /** HS256 needs at least 256 bits of key material. */
    private static final int MIN_KEY_BYTES = 32;

    @Value("${app.jwt.secret:}")
    private String configuredSecret;

    @Value("${app.jwt.expiration-ms:3600000}")
    private long jwtExpirationMs;

    private SecretKey signingKey;

    /** A challenge is a sign-in in progress, not a session. Five minutes is ample. */
    private static final long MFA_CHALLENGE_MS = 5 * 60 * 1000L;

    @PostConstruct
    void initialiseKey() {
        if (configuredSecret == null || configuredSecret.isBlank()) {
            signingKey = Keys.hmacShaKeyFor(randomKeyBytes());
            log.warn("""

                    ============================================================
                     No JWT_SECRET configured — generated a temporary signing key.
                     Every session ends when this process restarts, and a second
                     instance would reject this one's tokens.
                     Set JWT_SECRET (base64, 32+ bytes) before deploying:
                       openssl rand -base64 48
                    ============================================================""");
            return;
        }

        byte[] keyBytes = decode(configuredSecret);
        if (keyBytes.length < MIN_KEY_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET is too short: HS256 requires at least " + MIN_KEY_BYTES
                            + " bytes of key material, got " + keyBytes.length
                            + ". Generate one with: openssl rand -base64 48");
        }
        signingKey = Keys.hmacShaKeyFor(keyBytes);
        log.info("JWT signing key loaded from configuration ({} bytes)", keyBytes.length);
    }

    /** Accepts base64 and falls back to raw bytes, so a plain passphrase also works. */
    private static byte[] decode(String secret) {
        try {
            return Decoders.BASE64.decode(secret);
        } catch (IllegalArgumentException e) {
            return secret.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static byte[] randomKeyBytes() {
        byte[] bytes = new byte[48];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    /** Exposed for the startup self-check; never logs the key itself. */
    public boolean isUsingGeneratedKey() {
        return configuredSecret == null || configuredSecret.isBlank();
    }

    public String generateToken(String username, String role) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtExpirationMs);

        return Jwts.builder()
                .subject(username)
                .claim("role", role)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * A token that proves only one thing: this username's password was accepted.
     *
     * <p>It carries no role and is marked with {@code purpose=mfa}, so
     * {@link #validateToken} accepting it is not enough to reach anything —
     * JwtAuthenticationFilter refuses it, and only the second sign-in stage will
     * exchange it. Short-lived because it is a half-finished sign-in left in a
     * browser.
     */
    public String generateMfaChallengeToken(String username) {
        Date now = new Date();
        return Jwts.builder()
                .subject(username)
                .claim("purpose", "mfa")
                .issuedAt(now)
                .expiration(new Date(now.getTime() + MFA_CHALLENGE_MS))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    /** The username inside a challenge token, or null if it is not one. */
    public String getUsernameFromChallenge(String token) {
        try {
            var claims = Jwts.parser().verifyWith(signingKey).build()
                    .parseSignedClaims(token).getPayload();
            return "mfa".equals(claims.get("purpose", String.class)) ? claims.getSubject() : null;
        } catch (JwtException | IllegalArgumentException ex) {
            return null;
        }
    }

    /** True when the token is a half-finished sign-in rather than a session. */
    public boolean isChallengeToken(String token) {
        try {
            return "mfa".equals(Jwts.parser().verifyWith(signingKey).build()
                    .parseSignedClaims(token).getPayload().get("purpose", String.class));
        } catch (JwtException | IllegalArgumentException ex) {
            return false;
        }
    }

    public String getUsernameFromToken(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
    }

    public String getRoleFromToken(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .get("role", String.class);
    }

    public boolean validateToken(String authToken) {
        try {
            Jwts.parser().verifyWith(signingKey).build().parseSignedClaims(authToken);
            return true;
        } catch (JwtException | IllegalArgumentException ex) {
            return false;
        }
    }

    /** Base64 helper used by the startup banner to print a suggested key. */
    static String suggestKey() {
        return Base64.getEncoder().encodeToString(randomKeyBytes());
    }
}
