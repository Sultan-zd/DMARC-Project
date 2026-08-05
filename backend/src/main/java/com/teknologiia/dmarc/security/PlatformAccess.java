package com.teknologiia.dmarc.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Who may see the platform console.
 *
 * <p>Read from configuration, never from the database. An organization
 * administrator manages their own team; being able to grant themselves a view
 * across every tenant would make that role something quite different. Operator
 * status therefore comes from the deployment — whoever can set an environment
 * variable already runs the server.
 *
 * <p>With nothing configured, nobody qualifies and the console is simply absent.
 */
@Component
@Slf4j
public class PlatformAccess {

    private final Set<String> operators;

    public PlatformAccess(@Value("${app.platform.operators:}") String configured) {
        this.operators = configured == null || configured.isBlank()
                ? Set.of()
                : Arrays.stream(configured.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(s -> s.toLowerCase(Locale.ROOT))
                        .collect(Collectors.toUnmodifiableSet());

        if (operators.isEmpty()) {
            log.info("No PLATFORM_OPERATORS configured — the platform console is disabled.");
        } else {
            log.info("Platform console available to {} operator account(s)", operators.size());
        }
    }

    public boolean isOperator(String username) {
        return username != null && operators.contains(username.toLowerCase(Locale.ROOT));
    }
}
