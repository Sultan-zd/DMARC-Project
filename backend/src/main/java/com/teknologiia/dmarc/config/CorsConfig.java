package com.teknologiia.dmarc.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Cross-origin rules for the API.
 *
 * <p>The configured list is for genuinely different origins — the Vite dev server on
 * its own port. A request from the application's <em>own</em> origin is always
 * allowed, whatever that origin happens to be.
 *
 * <p>That second rule exists because of a real failure. Vite emits
 * {@code <script type="module" crossorigin>} and a {@code crossorigin} stylesheet
 * link. The attribute puts those fetches in CORS mode, so the browser sends an
 * {@code Origin} header even though the files come from the same site. Deployed
 * behind a tunnel, that origin was not on the configured list, the CORS filter
 * answered 403 with an empty body, and the dashboard rendered as a blank page —
 * with nothing in the interface saying why. Any new deployment address would have
 * done the same.
 */
@Configuration
public class CorsConfig {

    @Value("${app.cors.origins}")
    private String[] allowedOrigins;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        return this::configurationFor;
    }

    private CorsConfiguration configurationFor(HttpServletRequest request) {
        CorsConfiguration configuration = new CorsConfiguration();

        List<String> origins = new ArrayList<>(List.of(allowedOrigins));
        String own = ownOrigin(request);
        if (own != null && !origins.contains(own)) {
            origins.add(own);
        }

        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        return configuration;
    }

    /**
     * The request's {@code Origin}, but only when it is this site addressing itself.
     *
     * <p>Compared against the {@code Host} header rather than rebuilt from the
     * servlet request: behind a proxy the server sees its own port, while the
     * browser sends the public address, and the two would never match. The host
     * header is what both sides agree on.
     */
    private String ownOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        String host = request.getHeader("Host");
        if (origin == null || host == null) {
            return null;
        }

        try {
            URI uri = URI.create(origin);
            String authority = uri.getPort() == -1
                    ? uri.getHost()
                    : uri.getHost() + ":" + uri.getPort();
            return host.equalsIgnoreCase(authority) ? origin : null;
        } catch (IllegalArgumentException e) {
            // A malformed Origin is nobody's own origin.
            return null;
        }
    }
}
