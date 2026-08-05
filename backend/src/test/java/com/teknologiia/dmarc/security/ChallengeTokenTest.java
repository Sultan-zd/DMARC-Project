package com.teknologiia.dmarc.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A half-finished sign-in must not be a session.
 *
 * <p>The challenge token issued after the password stage is signed with the same key
 * as a real session token, so {@code validateToken} accepts both. What separates
 * them is the {@code purpose} claim, and {@link JwtAuthenticationFilter} refusing
 * anything carrying it. Without that check, learning a password would be enough to
 * walk straight past the second factor — the token handed out for failing to
 * complete a sign-in would itself open every endpoint.
 */
class ChallengeTokenTest {

    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        provider = new JwtTokenProvider();
        ReflectionTestUtils.setField(provider, "configuredSecret",
                "ZG1hcmMtZGFzaGJvYXJkIHRlc3Qgc2lnbmluZyBrZXksIG5vdCB1c2VkIG91dHNpZGUgdGVzdHM=");
        ReflectionTestUtils.setField(provider, "jwtExpirationMs", 3_600_000L);
        provider.initialiseKey();
    }

    @Test
    @DisplayName("a challenge token is recognisable as one")
    void challengeIsMarked() {
        String challenge = provider.generateMfaChallengeToken("sultan");

        assertThat(provider.isChallengeToken(challenge)).isTrue();
        assertThat(provider.getUsernameFromChallenge(challenge)).isEqualTo("sultan");
    }

    @Test
    @DisplayName("a session token is not mistaken for a challenge")
    void sessionIsNotAChallenge() {
        String session = provider.generateToken("sultan", "ADMIN");

        assertThat(provider.isChallengeToken(session)).isFalse();
        // Nor does it satisfy the second sign-in stage, which would let somebody
        // exchange a session they already hold for another one.
        assertThat(provider.getUsernameFromChallenge(session)).isNull();
    }

    @Test
    @DisplayName("both are validly signed, which is exactly why the purpose claim matters")
    void bothPassSignatureValidation() {
        assertThat(provider.validateToken(provider.generateToken("sultan", "ADMIN"))).isTrue();
        assertThat(provider.validateToken(provider.generateMfaChallengeToken("sultan"))).isTrue();
    }

    @Test
    @DisplayName("a challenge carries no role, so it could not authorise anything either")
    void challengeCarriesNoRole() {
        assertThat(provider.getRoleFromToken(provider.generateMfaChallengeToken("sultan"))).isNull();
    }

    @Test
    @DisplayName("nonsense is neither a session nor a challenge")
    void rejectsRubbish() {
        assertThat(provider.isChallengeToken("not-a-token")).isFalse();
        assertThat(provider.getUsernameFromChallenge("not-a-token")).isNull();
        assertThat(provider.validateToken("not-a-token")).isFalse();
    }

    @Test
    @DisplayName("a token signed with another key is refused")
    void rejectsForeignSignature() {
        JwtTokenProvider other = new JwtTokenProvider();
        ReflectionTestUtils.setField(other, "configuredSecret",
                "YW5vdGhlciBzaWduaW5nIGtleSBlbnRpcmVseSwgbG9uZyBlbm91Z2ggdG8gcGFzcw==");
        ReflectionTestUtils.setField(other, "jwtExpirationMs", 3_600_000L);
        other.initialiseKey();

        String foreign = other.generateMfaChallengeToken("sultan");

        assertThat(provider.isChallengeToken(foreign)).isFalse();
        assertThat(provider.getUsernameFromChallenge(foreign)).isNull();
    }
}
