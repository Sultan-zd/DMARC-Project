package com.teknologiia.dmarc.config;

import com.teknologiia.dmarc.model.Organization;
import com.teknologiia.dmarc.model.User;
import com.teknologiia.dmarc.repository.OrganizationRepository;
import com.teknologiia.dmarc.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Creates the first administrator, and nothing else.
 *
 * <p>This class used to seed 120 invented aggregate reports for example.com and
 * test.org, two alerts about them, and two accounts sharing the password "demo".
 * All of it is gone. Invented traffic is indistinguishable from real traffic once
 * it is in the database — it lands in the same charts, the same exports and the
 * same PDF a customer reads — and a deployment that ships with a way to inject it
 * will eventually inject it. Reports now come only from the mailbox poller or from
 * a file somebody uploads.
 */
@Component
@Slf4j
public class DataInitializer implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final OrganizationRepository organizationRepository;

    @Value("${app.admin.username}")
    private String adminUsername;

    @Value("${app.admin.password:}")
    private String adminPassword;

    @Value("${app.admin.email:admin@teknologiia.com}")
    private String adminEmail;

    @Value("${app.admin.organization:Teknologiia}")
    private String adminOrganization;

    public DataInitializer(UserRepository userRepository, PasswordEncoder passwordEncoder,
                           OrganizationRepository organizationRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.organizationRepository = organizationRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Bootstrapping means getting the first administrator in, not keeping an
        // account named "admin" alive forever. Checking only for that username meant
        // deleting it brought it straight back on the next restart, with a fresh
        // generated password in the log and nobody expecting it.
        if (userRepository.count() > 0) {
            return;
        }

        // The first account needs an organization to belong to. An existing one is
        // reused so a restart never splits a team in two.
        Organization organization = organizationRepository.findAll().stream().findFirst()
                .orElseGet(() -> organizationRepository.save(
                        Organization.builder().name(adminOrganization).build()));

        // No password in the tracked configuration: when ADMIN_PASSWORD is unset a
        // strong one is generated and printed once, at the moment the account is
        // created. It is never recoverable afterwards — only resettable.
        boolean generated = adminPassword == null || adminPassword.isBlank();
        String password = generated ? randomPassword() : adminPassword;

        userRepository.save(User.builder()
                .organization(organization)
                .username(adminUsername)
                .email(adminEmail)
                .hashedPassword(passwordEncoder.encode(password))
                .role("ADMIN")
                .active(true)
                .build());

        if (generated) {
            log.warn("""

                    ============================================================
                     Created the administrator account '{}'.
                     Generated password: {}
                     This is shown once and is not stored in clear anywhere.
                     Set ADMIN_PASSWORD to choose it yourself instead.
                    ============================================================""",
                    adminUsername, password);
        }
    }

    private String randomPassword() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
