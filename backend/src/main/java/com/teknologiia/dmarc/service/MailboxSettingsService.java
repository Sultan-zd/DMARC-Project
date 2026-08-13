package com.teknologiia.dmarc.service;

import com.teknologiia.dmarc.dto.mailbox.MailboxSettingsRequest;
import com.teknologiia.dmarc.dto.mailbox.MailboxSettingsResponse;
import com.teknologiia.dmarc.model.MailboxKind;
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
                        false, MailboxKind.IMAP, null, 993, null, null, null,
                        true, true, null, null, null,
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

        MailboxKind kind = MailboxKind.orDefault(request.kind());

        boolean hasNewSecret = request.password() != null && !request.password().isBlank();
        // Switching provider invalidates whatever was stored: an IMAP password is
        // not a client secret. Asking again is the only correct behaviour, and
        // silently keeping the old value would fail later with a puzzling message.
        boolean kindChanged = settings.getId() != null
                && MailboxKind.orDefault(settings.getKind()) != kind;

        if (!hasNewSecret && (settings.getPasswordCipher() == null || kindChanged)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, kind == MailboxKind.MICROSOFT_GRAPH
                    ? "The application's client secret is needed to configure a Microsoft 365 mailbox."
                    : "A password is needed the first time this mailbox is configured.");
        }
        if (hasNewSecret) {
            // Refuses outright when no key is configured rather than storing in clear.
            settings.setPasswordCipher(cipher.encrypt(request.password()));
        }

        settings.setKind(kind);
        settings.setUsername(request.username().trim());
        settings.setPollingEnabled(request.pollingEnabled());

        if (kind == MailboxKind.MICROSOFT_GRAPH) {
            applyGraph(settings, request);
        } else {
            applyImap(settings, request);
        }

        MailboxSettings saved = repository.save(settings);
        log.info("{} mailbox {} configured for organization {}",
                kind, saved.getUsername(), organizationId);
        return toResponse(saved);
    }

    /**
     * Microsoft 365, read as a registered application.
     *
     * <p>Host and port are set rather than left to the caller: they are not
     * configurable for Graph, the columns are not nullable, and writing the real
     * endpoint keeps the row honest about what it describes.
     */
    private void applyGraph(MailboxSettings settings, MailboxSettingsRequest request) {
        String tenantId = trimmed(request.tenantId());
        String clientId = trimmed(request.clientId());
        if (tenantId == null || clientId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A Microsoft 365 mailbox needs the directory (tenant) id and the "
                            + "application (client) id of the Entra ID registration.");
        }
        if (!settings.getUsername().contains("@")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "For Microsoft 365, give the full address of the mailbox to read — "
                            + "for example dmarcreports@yourcompany.com.");
        }
        settings.setTenantId(tenantId);
        settings.setClientId(clientId);
        settings.setHost("graph.microsoft.com");
        settings.setPort(443);
        settings.setUseSsl(true);
    }

    private void applyImap(MailboxSettings settings, MailboxSettingsRequest request) {
        String host = trimmed(request.host());
        if (host == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "An IMAP mailbox needs a host, for example imap.gmail.com.");
        }
        if (request.port() < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "An IMAP mailbox needs a port, usually 993.");
        }
        settings.setHost(host);
        settings.setPort(request.port());
        settings.setUseSsl(request.useSsl());
        // Left over from a previous Graph configuration; they mean nothing here.
        settings.setTenantId(null);
        settings.setClientId(null);
    }

    private static String trimmed(String value) {
        if (value == null) return null;
        String clean = value.trim();
        return clean.isEmpty() ? null : clean;
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
                true, MailboxKind.orDefault(s.getKind()), s.getHost(), s.getPort(),
                s.getUsername(), s.getTenantId(), s.getClientId(), s.isUseSsl(),
                s.isPollingEnabled(), s.getLastRunAt(), s.getLastRunSummary(), s.getLastRunOk(),
                pollIntervalMinutes, cipher.isConfigured());
    }
}
