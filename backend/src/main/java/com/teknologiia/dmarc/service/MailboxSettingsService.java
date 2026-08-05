package com.teknologiia.dmarc.service;

import com.teknologiia.dmarc.dto.mailbox.MailboxSettingsRequest;
import com.teknologiia.dmarc.dto.mailbox.MailboxSettingsResponse;
import com.teknologiia.dmarc.model.MailboxSettings;
import com.teknologiia.dmarc.repository.MailboxSettingsRepository;
import com.teknologiia.dmarc.repository.OrganizationRepository;
import com.teknologiia.dmarc.security.SecretCipher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Each organization's own report mailbox.
 *
 * <p>Every read and write is keyed on the caller's organization id, so one team's
 * credentials are unreachable from another's session.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MailboxSettingsService {

    private final MailboxSettingsRepository repository;
    private final OrganizationRepository organizationRepository;
    private final SecretCipher cipher;

    @Value("${app.imap.polling-interval-minutes:15}")
    private int pollIntervalMinutes;

    @Transactional(readOnly = true)
    public MailboxSettingsResponse get(Long organizationId) {
        return repository.findByOrganizationId(organizationId)
                .map(this::toResponse)
                .orElseGet(() -> new MailboxSettingsResponse(
                        false, null, 993, null, true, true, null, null, null,
                        pollIntervalMinutes, cipher.isConfigured()));
    }

    @Transactional(readOnly = true)
    public Optional<MailboxSettings> forOrganization(Long organizationId) {
        return repository.findByOrganizationId(organizationId);
    }

    /**
     * Stores or updates the mailbox.
     *
     * <p>Omitting the password keeps the one already held, so an administrator can
     * correct a typo in the host without re-entering a credential they may not have
     * to hand.
     */
    @Transactional
    public MailboxSettingsResponse save(Long organizationId, MailboxSettingsRequest request) {
        MailboxSettings settings = repository.findByOrganizationId(organizationId)
                .orElseGet(() -> MailboxSettings.builder()
                        .organization(organizationRepository.getReferenceById(organizationId))
                        .build());

        boolean hasNewPassword = request.password() != null && !request.password().isBlank();
        if (!hasNewPassword && settings.getPasswordCipher() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A password is needed the first time this mailbox is configured.");
        }
        if (hasNewPassword) {
            // Refuses outright when no key is configured rather than storing in clear.
            settings.setPasswordCipher(cipher.encrypt(request.password()));
        }

        settings.setHost(request.host().trim());
        settings.setPort(request.port());
        settings.setUsername(request.username().trim());
        settings.setUseSsl(request.useSsl());
        settings.setPollingEnabled(request.pollingEnabled());

        MailboxSettings saved = repository.save(settings);
        log.info("Mailbox {} configured for organization {}", saved.getUsername(), organizationId);
        return toResponse(saved);
    }

    @Transactional
    public void remove(Long organizationId) {
        repository.deleteByOrganizationId(organizationId);
        log.info("Mailbox settings removed for organization {}", organizationId);
    }

    /** Records how the last run went, so a mailbox failing quietly does not stay quiet. */
    @Transactional
    public void recordRun(Long organizationId, boolean ok, String summary) {
        repository.findByOrganizationId(organizationId).ifPresent(settings -> {
            settings.setLastRunAt(LocalDateTime.now(ZoneOffset.UTC));
            settings.setLastRunOk(ok);
            settings.setLastRunSummary(summary != null && summary.length() > 500
                    ? summary.substring(0, 500) : summary);
            repository.save(settings);
        });
    }

    public String password(MailboxSettings settings) {
        return cipher.decrypt(settings.getPasswordCipher());
    }

    private MailboxSettingsResponse toResponse(MailboxSettings s) {
        return new MailboxSettingsResponse(
                true, s.getHost(), s.getPort(), s.getUsername(), s.isUseSsl(),
                s.isPollingEnabled(), s.getLastRunAt(), s.getLastRunSummary(), s.getLastRunOk(),
                pollIntervalMinutes, cipher.isConfigured());
    }
}
