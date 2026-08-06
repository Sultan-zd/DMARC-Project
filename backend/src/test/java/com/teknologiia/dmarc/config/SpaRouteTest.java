package com.teknologiia.dmarc.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Every page the router knows about must survive being reloaded.
 *
 * <p>The dashboard routes in the browser, so the server has to answer each of those
 * paths with the HTML shell. The security configuration lists them by hand, and
 * {@code /platform} was left off it — which produced a page reachable by clicking
 * and broken by pressing F5, because a browser navigating directly carries no
 * Authorization header and the request fell through to "authenticated".
 *
 * <p>Serving the shell grants nothing on its own: it is the same file for everyone,
 * and what it then displays depends entirely on what the API answers. The routes
 * are asserted not to be refused rather than asserted to be 200, so the test still
 * means something on a checkout where the frontend has not been built and the shell
 * is genuinely absent.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SpaRouteTest {

    @Autowired private MockMvc mockMvc;

    @ParameterizedTest
    @ValueSource(strings = {
            "/", "/login", "/register", "/verify", "/invitation", "/change-password",
            "/dashboard", "/reports", "/alerts", "/analysis", "/admin", "/settings",
            "/platform", "/scan/example.com",
    })
    @DisplayName("a client-side route is never refused to a browser reloading it")
    void clientRoutesAreServed(String route) throws Exception {
        int status = mockMvc.perform(get(route)).andReturn().getResponse().getStatus();

        assertThat(status)
                .as("%s is a page in the router; refusing it breaks reload and bookmarks", route)
                .isNotIn(401, 403);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/platform/overview",
            "/api/database/tables",
            "/api/admin/users",
            "/api/reports",
    })
    @DisplayName("an API path is still refused without a session")
    void apiRoutesStillRequireAuthentication(String route) throws Exception {
        // The other half of the same rule: widening the page list must not have
        // widened anything that answers with data.
        int status = mockMvc.perform(get(route)).andReturn().getResponse().getStatus();

        assertThat(status)
                .as("%s must not be readable without signing in", route)
                .isIn(401, 403);
    }
}
