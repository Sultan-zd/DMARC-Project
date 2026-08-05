package com.teknologiia.dmarc.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who may see across every tenant.
 *
 * <p>The list comes from deployment configuration and nowhere else. An organization
 * administrator can create accounts, change roles and hand out invitations inside
 * their own team — none of which may ever become a way to read the service as a
 * whole. Keeping the answer outside the database means no amount of writing to it
 * changes who qualifies.
 */
class PlatformAccessTest {

    @Test
    @DisplayName("nobody qualifies when nothing is configured")
    void closedByDefault() {
        PlatformAccess access = new PlatformAccess("");

        assertThat(access.isOperator("anyone")).isFalse();
        assertThat(access.isOperator("admin")).isFalse();
    }

    @Test
    @DisplayName("null configuration is the same as none")
    void nullConfiguration() {
        assertThat(new PlatformAccess(null).isOperator("admin")).isFalse();
    }

    @Test
    @DisplayName("a configured operator qualifies, and only them")
    void configuredOperator() {
        PlatformAccess access = new PlatformAccess("barhoum");

        assertThat(access.isOperator("barhoum")).isTrue();
        assertThat(access.isOperator("bassem")).isFalse();
    }

    @Test
    @DisplayName("several operators, spacing ignored")
    void severalOperators() {
        PlatformAccess access = new PlatformAccess(" barhoum , sultan ,, bassem ");

        assertThat(access.isOperator("barhoum")).isTrue();
        assertThat(access.isOperator("sultan")).isTrue();
        assertThat(access.isOperator("bassem")).isTrue();
        assertThat(access.isOperator("")).isFalse();
    }

    @Test
    @DisplayName("usernames match regardless of capitalisation")
    void caseInsensitive() {
        PlatformAccess access = new PlatformAccess("Barhoum");

        assertThat(access.isOperator("barhoum")).isTrue();
        assertThat(access.isOperator("BARHOUM")).isTrue();
    }

    @Test
    @DisplayName("no username at all is not an operator")
    void anonymousIsNotAnOperator() {
        assertThat(new PlatformAccess("barhoum").isOperator(null)).isFalse();
    }

    @Test
    @DisplayName("a name that merely contains an operator's is not that operator")
    void noPartialMatches() {
        PlatformAccess access = new PlatformAccess("barhoum");

        // Registration is open, so anyone can pick a username. It must not be
        // possible to approach operator status by choosing a similar one.
        assertThat(access.isOperator("barhoum2")).isFalse();
        assertThat(access.isOperator("xbarhoum")).isFalse();
        assertThat(access.isOperator("barhou")).isFalse();
    }
}
