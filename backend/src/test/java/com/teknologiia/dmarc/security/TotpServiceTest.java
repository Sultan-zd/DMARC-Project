package com.teknologiia.dmarc.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one-time password algorithm, checked against RFC 6238's own test vectors.
 *
 * <p>Worth pinning precisely: a TOTP implementation that is subtly wrong still
 * produces six plausible digits, and the only symptom is that nobody can ever sign
 * in — or, far worse, that codes verify when they should not.
 */
class TotpServiceTest {

    private final TotpService totp = new TotpService();

    /**
     * RFC 6238 Appendix B publishes vectors for the ASCII secret "12345678901234567890".
     * The RFC lists 8-digit values; this implementation emits 6, which is the last six
     * digits of each — the truncation modulo differs only in width.
     */
    private static final String RFC_SECRET =
            TotpService.base32Encode("12345678901234567890".getBytes());

    @ParameterizedTest
    @CsvSource({
            "59,          287082",
            "1111111109,  081804",
            "1111111111,  050471",
            "1234567890,  005924",
            "2000000000,  279037",
    })
    @DisplayName("matches the RFC 6238 test vectors")
    void matchesRfcVectors(long epochSecond, String expected) {
        assertThat(totp.generate(RFC_SECRET, epochSecond / 30)).isEqualTo(expected);
    }

    @Test
    @DisplayName("accepts the code for the current moment")
    void acceptsCurrentCode() {
        String secret = totp.generateSecret();
        String code = totp.generate(secret, Instant.now().getEpochSecond() / 30);

        assertThat(totp.verify(secret, code)).isTrue();
    }

    @Test
    @DisplayName("accepts one step either side, for clocks slightly out")
    void acceptsAdjacentSteps() {
        String secret = totp.generateSecret();
        long now = Instant.now().getEpochSecond() / 30;

        assertThat(totp.verify(secret, totp.generate(secret, now - 1))).isTrue();
        assertThat(totp.verify(secret, totp.generate(secret, now + 1))).isTrue();
    }

    @Test
    @DisplayName("refuses a code from further away than the window allows")
    void refusesDistantCodes() {
        String secret = totp.generateSecret();
        long now = Instant.now().getEpochSecond() / 30;

        // Two steps is a minute ago: past the point where a stolen code should work.
        assertThat(totp.verify(secret, totp.generate(secret, now - 2))).isFalse();
        assertThat(totp.verify(secret, totp.generate(secret, now + 2))).isFalse();
    }

    @Test
    @DisplayName("refuses another secret's code")
    void refusesForeignCodes() {
        String mine = totp.generateSecret();
        String theirs = totp.generateSecret();
        String code = totp.generate(theirs, Instant.now().getEpochSecond() / 30);

        assertThat(totp.verify(mine, code)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"''", "'12345'", "'1234567'", "'abcdef'", "'12 34 56'", "'000-000'"})
    @DisplayName("refuses anything that is not six digits")
    void refusesMalformedInput(String code) {
        // "12 34 56" is stripped to six digits and then simply will not match; the
        // point is that none of these throw or accidentally pass.
        assertThat(totp.verify(totp.generateSecret(), code)).isFalse();
    }

    @Test
    @DisplayName("survives a null secret or code without throwing")
    void toleratesNulls() {
        assertThat(totp.verify(null, "123456")).isFalse();
        assertThat(totp.verify(totp.generateSecret(), null)).isFalse();
    }

    @Test
    @DisplayName("secrets are base32 and long enough to be worth generating")
    void secretsAreUsable() {
        String secret = totp.generateSecret();

        assertThat(secret).matches("[A-Z2-7]+");
        // 20 bytes at 5 bits per character.
        assertThat(secret).hasSize(32);
        assertThat(totp.generateSecret()).isNotEqualTo(secret);
    }

    @Test
    @DisplayName("base32 survives a round trip")
    void base32RoundTrips() {
        byte[] original = "12345678901234567890".getBytes();

        assertThat(TotpService.base32Decode(TotpService.base32Encode(original)))
                .isEqualTo(original);
    }

    @Test
    @DisplayName("the enrolment URI carries what an authenticator app needs")
    void enrolmentUriIsComplete() {
        String uri = totp.otpauthUri("ABCDEFGHIJKLMNOP", "sultan@teknologiia.com", "Teknologiia DMARC");

        assertThat(uri).startsWith("otpauth://totp/")
                .contains("secret=ABCDEFGHIJKLMNOP")
                .contains("issuer=Teknologiia+DMARC")
                .contains("digits=6")
                .contains("period=30");
        // The account has to survive encoding: an unescaped @ breaks some readers.
        assertThat(uri).contains("sultan%40teknologiia.com");
    }
}
