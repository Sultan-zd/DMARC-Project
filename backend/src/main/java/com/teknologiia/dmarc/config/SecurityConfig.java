package com.teknologiia.dmarc.config;

import com.teknologiia.dmarc.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public org.springframework.security.authentication.AuthenticationManager authenticationManager(org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> {})
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/login").permitAll()
                // Second sign-in stage: the caller has no session yet, only the
                // short-lived challenge the password stage issued.
                .requestMatchers("/api/auth/login/2fa").permitAll()
                // Self-service sign-up and its email confirmation, both pre-authentication.
                .requestMatchers("/api/auth/register", "/api/auth/verify").permitAll()
                // Recovering a forgotten password cannot require being signed in —
                // not being able to sign in is the situation. What stands in for a
                // session is the emailed token, which only reaches the mailbox the
                // account was registered with.
                .requestMatchers("/api/auth/forgot-password", "/api/auth/reset-password").permitAll()
                .requestMatchers("/api/health").permitAll()
                // Anonymous domain scanner. Rate limited and input-validated in
                // PublicScanController; nothing here reads or writes user data.
                .requestMatchers("/api/public/**").permitAll()
                .requestMatchers("/api/docs/**", "/api/api-docs/**").permitAll()
                .requestMatchers("/error").permitAll()
                // The built dashboard, served from the same origin as the API. The
                // shell and its assets must load before anyone can sign in — they
                // are what shows the sign-in form.
                .requestMatchers("/", "/index.html", "/favicon.ico", "/assets/**",
                        "/brand/**", "/*.png", "/*.svg", "/*.webmanifest").permitAll()
                // What search engines fetch before anything else. Neither matches a
                // pattern above, so without this line both answer 403 — and a crawler
                // refused robots.txt is entitled to assume the whole site is closed
                // to it. The one file whose job is to say "you may index this" cannot
                // itself require a session.
                .requestMatchers("/robots.txt", "/sitemap.xml").permitAll()
                // Client-side routes: the SPA fallback answers these with the shell,
                // and what the shell then shows is decided by the token it holds.
                //
                // Serving the shell grants nothing. A browser navigating here carries
                // no Authorization header — the token lives in the page, not in a
                // cookie — so without these entries reloading a signed-in page answers
                // 403 with Spring's error shell rather than the application. Every
                // route the router knows about needs a line here; /platform was missed
                // and was therefore reachable by clicking but not by reloading.
                .requestMatchers("/login", "/register", "/verify", "/invitation",
                        "/change-password", "/forgot-password", "/reset-password",
                        "/dashboard", "/reports", "/alerts",
                        "/analysis", "/admin", "/settings", "/platform",
                        "/scan/**").permitAll()
                // ── Roles, enforced rather than merely labelled ──────────────
                // Previously only /api/admin/** was restricted, which left ANALYST
                // and VIEWER with identical permissions: a "read-only" account could
                // change anything a full member could.

                // Account management is the administrator's alone.
                .requestMatchers("/api/admin/users/**").hasRole("ADMIN")

                // Bringing data in, and acting on it, is for admins and analysts.
                // A viewer may read everything and change nothing.
                .requestMatchers("/api/admin/reports/upload", "/api/admin/ingest")
                        .hasAnyRole("ADMIN", "ANALYST")
                .requestMatchers(HttpMethod.POST, "/api/analysis/domain")
                        .hasAnyRole("ADMIN", "ANALYST")
                .requestMatchers(HttpMethod.PATCH, "/api/alerts/**")
                        .hasAnyRole("ADMIN", "ANALYST")

                // The platform console reads across every tenant, so it is not open
                // to an organization administrator at all. PlatformController checks
                // the configured operator list; this only insists on a session.
                .requestMatchers("/api/platform/**").authenticated()

                // Anything else under /api/admin stays administrator-only by default,
                // so a new endpoint added there is restricted until decided otherwise.
                .requestMatchers("/api/admin/**").hasRole("ADMIN")

                // Reading — dashboard, reports, exports, analysis history — is open to
                // every authenticated member of an organization.
                .anyRequest().authenticated()
            )
            .headers(headers -> headers
                    // SAMEORIGIN was only needed to frame the H2 console, which is gone.
                    // Back to the stricter default: this app is never framed.
                    .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny)
                    // Tells browsers to reach this origin over TLS only. Harmless over
                    // plain HTTP in development, since the header is ignored there.
                    .httpStrictTransportSecurity(hsts -> hsts
                            .includeSubDomains(true)
                            .maxAgeInSeconds(31_536_000))
                    .referrerPolicy(referrer -> referrer.policy(
                            ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                    // Written for a JSON-only API, this was 'default-src none' — which
                    // blocked every script, stylesheet and image the moment the same
                    // process began serving the dashboard itself. Each source below
                    // is something the page genuinely loads:
                    //   script/connect  own bundle, own API
                    //   style           own CSS, plus Google Fonts' stylesheet
                    //   font            gstatic, where that stylesheet points
                    //   img data:       the two-factor QR code is an inline PNG
                    // Nothing else is permitted, and the page is still never framed.
                    .contentSecurityPolicy(csp -> csp.policyDirectives(
                            "default-src 'self'; "
                            + "script-src 'self'; "
                            + "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; "
                            + "font-src 'self' https://fonts.gstatic.com; "
                            + "img-src 'self' data:; "
                            + "connect-src 'self'; "
                            + "object-src 'none'; "
                            + "frame-ancestors 'none'; "
                            + "base-uri 'none'; "
                            + "form-action 'self'")))
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
