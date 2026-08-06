package com.teknologiia.dmarc.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * Turns a bearer token into an authenticated request, or refuses to.
 *
 * <p>The account is loaded from the database on every request rather than trusted
 * from the token's claims. That is what makes a role change take effect at once,
 * and what makes a deleted account stop working the moment it is deleted — the
 * lookup simply finds nothing.
 *
 * <p>Two things the token's own validity does not cover, both checked here.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider tokenProvider;
    private final CustomUserDetailsService customUserDetailsService;

    public JwtAuthenticationFilter(JwtTokenProvider tokenProvider,
                                   CustomUserDetailsService customUserDetailsService) {
        this.tokenProvider = tokenProvider;
        this.customUserDetailsService = customUserDetailsService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String jwt = getJwtFromRequest(request);

            // A challenge token is signed by the same key and would otherwise pass
            // validateToken — which would make it a bearer token and let anyone who
            // knows a password skip the second factor entirely. Only the second
            // sign-in stage may exchange one.
            if (StringUtils.hasText(jwt)
                    && tokenProvider.validateToken(jwt)
                    && !tokenProvider.isChallengeToken(jwt)) {

                String username = tokenProvider.getUsernameFromToken(jwt);
                AuthenticatedUser userDetails =
                        (AuthenticatedUser) customUserDetailsService.loadUserByUsername(username);

                if (isUsable(userDetails, jwt)) {
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(
                                    userDetails, null, userDetails.getAuthorities());
                    authentication.setDetails(
                            new WebAuthenticationDetailsSource().buildDetails(request));

                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            }
        } catch (Exception ex) {
            logger.error("Could not set user authentication in security context", ex);
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Whether this token still opens this account.
     *
     * <p><strong>Disabled accounts.</strong> Signing in goes through Spring's
     * authentication provider, which refuses a disabled account. Presenting an
     * existing token does not — it arrives here, and this filter used to build the
     * authentication without ever asking. So an account disabled by an
     * administrator carried on working until its token expired, which is exactly
     * the hour in which disabling it mattered.
     *
     * <p><strong>Revoked sessions.</strong> A JWT cannot be called back once
     * signed. The account carries the instant its sessions were last invalidated,
     * and any token issued before it is refused — which is what makes changing a
     * password, or pressing sign out everywhere, end the sessions already open.
     */
    private boolean isUsable(AuthenticatedUser user, String jwt) {
        if (!user.isEnabled()) {
            logger.debug("Refusing a token for a disabled account");
            return false;
        }

        var revokedAt = user.getTokensValidFrom();
        if (revokedAt == null) {
            return true;
        }

        Instant issuedAt = tokenProvider.getIssuedAt(jwt);
        if (issuedAt == null) {
            return false;
        }

        // Strictly after, not "not before".
        //
        // A JWT's iat has one-second resolution, so a token minted in the same
        // second as the revocation cannot be ordered against it. Resolving that tie
        // in the token's favour was the first attempt, and it was wrong: pressing
        // sign out everywhere returns in well under a second, so the very session
        // that asked kept working — the one thing the button promises to end.
        //
        // Resolving it against the token costs nothing, because the only session
        // that legitimately needs to survive a revocation is the one a caller mints
        // deliberately afterwards, and those are dated a second past the watermark
        // on purpose. Anything else in that second is a session that was already
        // open, which is exactly what revocation is for.
        Instant watermark = revokedAt.toInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
        return issuedAt.truncatedTo(ChronoUnit.SECONDS).isAfter(watermark);
    }

    private String getJwtFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
