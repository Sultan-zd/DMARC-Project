package com.teknologiia.dmarc.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A site must never be blocked from addressing itself.
 *
 * <p>Vite marks its module script and stylesheet {@code crossorigin}, which puts
 * those fetches in CORS mode and makes the browser send an {@code Origin} header
 * for same-origin files. Behind a tunnel that origin was not on the configured
 * list, the filter answered 403 with an empty body, and the whole dashboard
 * rendered blank — the interface said nothing at all, because none of it loaded.
 */
class CorsConfigTest {

    private final CorsConfig config = new CorsConfig();

    CorsConfigTest() {
        ReflectionTestUtils.setField(config, "allowedOrigins",
                new String[]{"http://localhost:5173"});
    }

    private CorsConfiguration forRequest(String origin, String host) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/assets/app.js");
        if (origin != null) request.addHeader("Origin", origin);
        if (host != null) request.addHeader("Host", host);
        return config.corsConfigurationSource().getCorsConfiguration(request);
    }

    @Test
    @DisplayName("the site's own origin is allowed, whatever address it is served at")
    void ownOriginIsAlwaysAllowed() {
        var deployed = forRequest("https://pandora.ngrok-free.dev", "pandora.ngrok-free.dev");

        assertThat(deployed.getAllowedOrigins()).contains("https://pandora.ngrok-free.dev");
    }

    @Test
    @DisplayName("a port in the origin still counts as the same site")
    void ownOriginWithPort() {
        var local = forRequest("http://localhost:8000", "localhost:8000");

        assertThat(local.getAllowedOrigins()).contains("http://localhost:8000");
    }

    @Test
    @DisplayName("the configured origins keep working")
    void configuredOriginsRemain() {
        var dev = forRequest("http://localhost:5173", "localhost:8000");

        assertThat(dev.getAllowedOrigins()).contains("http://localhost:5173");
    }

    @Test
    @DisplayName("somebody else's origin is not allowed just because they asked")
    void foreignOriginIsRefused() {
        var attacker = forRequest("https://evil.test", "pandora.ngrok-free.dev");

        assertThat(attacker.getAllowedOrigins())
                .containsExactly("http://localhost:5173")
                .doesNotContain("https://evil.test");
    }

    @Test
    @DisplayName("a host that merely resembles the origin is not it")
    void lookalikeHostIsRefused() {
        // Same host, different port: a different origin, and the browser treats it so.
        var other = forRequest("https://pandora.ngrok-free.dev:8443", "pandora.ngrok-free.dev");

        assertThat(other.getAllowedOrigins()).doesNotContain("https://pandora.ngrok-free.dev:8443");
    }

    @Test
    @DisplayName("a request with no Origin needs no exception made for it")
    void noOriginHeader() {
        var plain = forRequest(null, "localhost:8000");

        assertThat(plain.getAllowedOrigins()).containsExactly("http://localhost:5173");
    }

    @Test
    @DisplayName("a malformed Origin is nobody's own origin")
    void malformedOrigin() {
        var broken = forRequest("://not a url", "localhost:8000");

        assertThat(broken.getAllowedOrigins()).containsExactly("http://localhost:5173");
    }
}
