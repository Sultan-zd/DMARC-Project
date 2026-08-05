package com.teknologiia.dmarc.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordPolicyTest {

    private final PasswordPolicy policy = new PasswordPolicy();

    @ParameterizedTest
    @ValueSource(strings = {
            "Tr0ub4dor&Cavalry",
            "Winter-Harbour-92!",
            "qX7#mLp2Rv!Kz9Wt",
    })
    @DisplayName("accepts long passwords mixing several character classes")
    void acceptsStrongPasswords(String password) {
        assertThat(policy.check(password, "alice", "alice@acme.test")).isEmpty();
    }

    @Test
    @DisplayName("refuses anything shorter than the minimum")
    void refusesShortPasswords() {
        assertThat(policy.check("Ab3!xY", "alice", null))
                .anyMatch(problem -> problem.contains("at least"));
    }

    @Test
    @DisplayName("refuses a password drawn from too few character classes")
    void refusesLowVariety() {
        assertThat(policy.check("aaaaeeeeiiiioooo", "alice", null))
                .anyMatch(problem -> problem.contains("Mix at least three"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"MyPassword2024!", "Azerty-Secure-1!", "Qwerty#Longer99"})
    @DisplayName("refuses passwords containing a commonly guessed word")
    void refusesCommonWords(String password) {
        assertThat(policy.check(password, "alice", null))
                .anyMatch(problem -> problem.contains("commonly guessed"));
    }

    @Test
    @DisplayName("refuses a password built from the username")
    void refusesUsernameInPassword() {
        assertThat(policy.check("Alice-Secure-42!", "alice", null))
                .anyMatch(problem -> problem.contains("username"));
    }

    @Test
    @DisplayName("refuses a password built from the email address")
    void refusesEmailInPassword() {
        assertThat(policy.check("Xk9#jbrown-Vault", "someone", "jbrown@acme.test"))
                .anyMatch(problem -> problem.contains("email"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Kx9!aaaaBcdefg", "Kx9!abcdEfghij", "Kx9!zyxwVutsrq"})
    @DisplayName("refuses long repeated or sequential runs")
    void refusesRuns(String password) {
        assertThat(policy.check(password, "alice", null))
                .anyMatch(problem -> problem.contains("runs"));
    }

    @Test
    @DisplayName("refuses an empty password with a single clear message")
    void refusesEmpty() {
        assertThat(policy.check("   ", "alice", null)).containsExactly("Enter a password.");
    }

    @Test
    @DisplayName("enforce throws on the first broken rule")
    void enforceThrows() {
        assertThatThrownBy(() -> policy.enforce("short", "alice", null))
                .hasMessageContaining("at least");
    }

    @Test
    @DisplayName("enforce is silent for an acceptable password")
    void enforcePasses() {
        policy.enforce("Winter-Harbour-92!", "alice", "alice@acme.test");
    }

    @Test
    @DisplayName("every generated password satisfies the policy it is generated against")
    void generatedPasswordsAlwaysPass() {
        // The generator and the validator must not be able to disagree.
        for (int i = 0; i < 300; i++) {
            String generated = policy.generate();
            assertThat(policy.check(generated, "alice", "alice@acme.test"))
                    .as("generated password %s", generated)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("generated passwords are distinct")
    void generatedPasswordsAreDistinct() {
        assertThat(java.util.stream.Stream.generate(policy::generate).limit(200).distinct().count())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("generated passwords avoid glyphs that are misread when copied by hand")
    void generatedPasswordsAvoidAmbiguousCharacters() {
        for (int i = 0; i < 100; i++) {
            assertThat(policy.generate()).doesNotContain("O", "0", "l", "1", "I");
        }
    }
}
