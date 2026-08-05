package com.teknologiia.dmarc.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainNameValidatorTest {

    @ParameterizedTest
    @CsvSource({
            "teknologiia.com,               teknologiia.com",
            "  TEKNOLOGIIA.COM  ,           teknologiia.com",
            "teknologiia.com.,              teknologiia.com",
            "mail.sub.teknologiia.co.uk,    mail.sub.teknologiia.co.uk"
    })
    @DisplayName("normalises case, whitespace and the trailing root dot")
    void normalisesPlainDomains(String input, String expected) {
        assertThat(DomainNameValidator.normalise(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "https://www.counter-strike.net/,        www.counter-strike.net",
            "http://store.steampowered.com,          store.steampowered.com",
            "https://teknologiia.com:8443/path?q=1,  teknologiia.com",
            "user@teknologiia.com,                   teknologiia.com"
    })
    @DisplayName("accepts pasted URLs and addresses by stripping to the hostname")
    void stripsUrlsToHostname(String input, String expected) {
        // The stored history shows people really do paste whole URLs into this field.
        assertThat(DomainNameValidator.normalise(input)).isEqualTo(expected);
    }

    @Test
    @DisplayName("converts internationalised names to punycode")
    void convertsUnicodeToPunycode() {
        assertThat(DomainNameValidator.normalise("bücher.de")).isEqualTo("xn--bcher-kva.de");
    }

    @ParameterizedTest
    @ValueSource(strings = {"192.168.1.1", "8.8.8.8", "127.0.0.1", "::1", "2001:db8::1"})
    @DisplayName("refuses IP addresses")
    void refusesIpAddresses(String input) {
        assertThatThrownBy(() -> DomainNameValidator.normalise(input))
                .isInstanceOf(DomainNameValidator.InvalidDomainException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "router.local", "db.internal", "app.corp",
            "printer.lan", "something.test", "foo.invalid", "site.onion"
    })
    @DisplayName("refuses suffixes that never resolve publicly")
    void refusesNonPublicSuffixes(String input) {
        // These would otherwise point the scanner at internal infrastructure.
        assertThatThrownBy(() -> DomainNameValidator.normalise(input))
                .isInstanceOf(DomainNameValidator.InvalidDomainException.class)
                .hasMessageContaining("publicly resolvable");
    }

    @Test
    @DisplayName("refuses a bare hostname such as localhost")
    void refusesSingleLabelNames() {
        // Caught by the two-label rule before the suffix list is reached, so the
        // message differs — the point is that it never reaches a resolver.
        assertThatThrownBy(() -> DomainNameValidator.normalise("localhost"))
                .isInstanceOf(DomainNameValidator.InvalidDomainException.class)
                .hasMessageContaining("full domain name");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "com", "-bad.com", "bad-.com", "a..b.com", "exa mple.com"})
    @DisplayName("refuses malformed input")
    void refusesMalformedInput(String input) {
        assertThatThrownBy(() -> DomainNameValidator.normalise(input))
                .isInstanceOf(DomainNameValidator.InvalidDomainException.class);
    }

    @Test
    @DisplayName("refuses a null domain")
    void refusesNull() {
        assertThatThrownBy(() -> DomainNameValidator.normalise(null))
                .isInstanceOf(DomainNameValidator.InvalidDomainException.class);
    }

    @Test
    @DisplayName("refuses names beyond the maximum length")
    void refusesOverlongNames() {
        String label = "a".repeat(60);
        String tooLong = (label + ".").repeat(5) + "com";

        assertThatThrownBy(() -> DomainNameValidator.normalise(tooLong))
                .isInstanceOf(DomainNameValidator.InvalidDomainException.class)
                .hasMessageContaining("too long");
    }

    @Test
    @DisplayName("refuses a label beyond 63 characters")
    void refusesOverlongLabel() {
        assertThatThrownBy(() -> DomainNameValidator.normalise("a".repeat(64) + ".com"))
                .isInstanceOf(DomainNameValidator.InvalidDomainException.class);
    }
}
