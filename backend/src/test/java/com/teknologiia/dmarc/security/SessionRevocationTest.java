package com.teknologiia.dmarc.security;

import com.teknologiia.dmarc.model.Organization;
import com.teknologiia.dmarc.model.User;
import com.teknologiia.dmarc.repository.OrganizationRepository;
import com.teknologiia.dmarc.repository.UserRepository;
import com.teknologiia.dmarc.service.AuthService;
import com.teknologiia.dmarc.service.SessionService;
import com.teknologiia.dmarc.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Ending a session that has already been handed out.
 *
 * <p>A JWT is valid until it expires and nothing in it can be changed afterwards,
 * so every one of these goes through the authentication filter with a real token
 * rather than testing a service in isolation. What matters is whether a request
 * carrying that token is honoured — which is the only thing an attacker holding
 * one cares about either.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SessionRevocationTest {

    private static final String PASSWORD = "Harbour-Lantern-Grey-41";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private SessionService sessionService;
    @Autowired private AuthService authService;
    @Autowired private UserService userService;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private String username;

    @BeforeEach
    void seed() {
        String id = UUID.randomUUID().toString().substring(0, 8);
        username = "sess-" + id;

        Organization organization = organizationRepository.save(
                Organization.builder().name("Sessions " + id).build());
        userRepository.save(User.builder()
                .organization(organization)
                .username(username)
                .email(id + "@sessions.test")
                .hashedPassword(passwordEncoder.encode(PASSWORD))
                .role("ADMIN")
                .active(true)
                .build());
    }

    /** A real session token for this account. */
    private String token() {
        return tokenProvider.generateToken(username, "ADMIN");
    }

    /** The status a request carrying that token gets from a page that needs one. */
    private int statusWith(String jwt) throws Exception {
        return mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + jwt))
                .andReturn().getResponse().getStatus();
    }

    private User reload() {
        return userRepository.findByUsername(username).orElseThrow();
    }

    // ─── The baseline ───────────────────────────────────────────────

    @Test
    @DisplayName("a fresh token opens the account")
    void freshTokenWorks() throws Exception {
        assertThat(statusWith(token())).isEqualTo(200);
    }

    // ─── Revocation ─────────────────────────────────────────────────

    @Test
    @DisplayName("a token issued before a revocation is refused")
    void revokedTokenIsRefused() throws Exception {
        String stolen = token();
        assertThat(statusWith(stolen)).isEqualTo(200);

        // A second of clearance: a JWT's iat is stored in whole seconds, so a token
        // minted in the same second as the watermark is genuinely ambiguous.
        sleepASecond();
        sessionService.revokeAll(username, "operator", "test");

        assertThat(statusWith(stolen))
                .as("this is the whole point: a token already handed out must stop working")
                .isIn(401, 403);
    }

    @Test
    @DisplayName("a token issued after a revocation still works")
    void tokenIssuedAfterRevocationWorks() throws Exception {
        sessionService.revokeAll(username, "operator", "test");
        sleepASecond();

        assertThat(statusWith(token()))
                .as("revoking must not lock the account out of signing in again")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("signing out everywhere includes the session that asked")
    void signOutEverywhereEndsTheCallersOwnSession() throws Exception {
        // Found in a browser, not in a test: the first attempt resolved a
        // same-second tie in the token's favour, and since the call returns in well
        // under a second, the session pressing the button survived it. Which is the
        // one thing "everywhere" promises to cover.
        String mine = token();

        mockMvc.perform(post("/api/auth/sign-out-everywhere")
                .header("Authorization", "Bearer " + mine));

        assertThat(statusWith(mine))
                .as("the session that asked to be signed out must be signed out")
                .isIn(401, 403);
    }

    @Test
    @DisplayName("changing a password keeps this session and ends the others")
    void passwordChangeKeepsTheCaller() throws Exception {
        String elsewhere = token();

        var revokedAt = userService.changeOwnPassword(
                username, PASSWORD, "Copper-Meridian-Vault-58");
        String replacement = authService.issueAfterRevocation(username, revokedAt).access_token();

        assertThat(statusWith(elsewhere))
                .as("the sessions opened with the old password must die")
                .isIn(401, 403);
        assertThat(statusWith(replacement))
                .as("doing the right thing must not throw you out for it")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("changing a password ends the sessions opened with the old one")
    void passwordChangeRevokes() throws Exception {
        String before = token();
        sleepASecond();

        userService.changeOwnPassword(username, PASSWORD, "Copper-Meridian-Vault-58");

        assertThat(statusWith(before)).isIn(401, 403);
        assertThat(reload().getTokensValidFrom()).isNotNull();
    }

    @Test
    @DisplayName("disabling an account cuts the session it already has")
    void disablingRevokes() throws Exception {
        // The defect this was written for: signing in goes through Spring's
        // authentication provider, which refuses a disabled account -- but
        // presenting an existing token does not, so a disabled account carried on
        // working for the rest of the token's hour. Which is exactly the hour in
        // which disabling it mattered.
        User user = reload();
        String held = token();
        assertThat(statusWith(held)).isEqualTo(200);

        user.setActive(false);
        userRepository.save(user);

        assertThat(statusWith(held))
                .as("a disabled account must not keep working until its token expires")
                .isIn(401, 403);
    }

    @Test
    @DisplayName("deleting an account cuts its session")
    void deletingRevokes() throws Exception {
        String held = token();
        userRepository.delete(reload());

        // Already true before any of this work -- the filter loads the account on
        // every request, so a deleted one simply is not found. Asserted so it stays
        // true if that lookup is ever cached.
        assertThat(statusWith(held)).isIn(401, 403);
    }

    @Test
    @DisplayName("revoking one account does not touch another")
    void revocationIsPerAccount() throws Exception {
        String other = "sess-other-" + UUID.randomUUID().toString().substring(0, 8);
        Organization organization = organizationRepository.save(
                Organization.builder().name("Other " + other).build());
        userRepository.save(User.builder()
                .organization(organization).username(other).email(other + "@sessions.test")
                .hashedPassword(passwordEncoder.encode(PASSWORD)).role("ADMIN").active(true).build());

        String otherToken = tokenProvider.generateToken(other, "ADMIN");
        sleepASecond();
        sessionService.revokeAll(username, "operator", "test");

        assertThat(statusWith(otherToken)).isEqualTo(200);
    }

    /**
     * The iat claim has one-second resolution, so a token and a watermark created in
     * the same second cannot be ordered. Waiting is the honest way to test across
     * that boundary — the alternative is asserting behaviour the format cannot
     * actually distinguish.
     */
    private static void sleepASecond() {
        try {
            Thread.sleep(1100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
