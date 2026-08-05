package com.teknologiia.dmarc.controller;

import com.teknologiia.dmarc.dto.mailbox.MailboxSettingsRequest;
import com.teknologiia.dmarc.dto.mailbox.MailboxSettingsResponse;
import com.teknologiia.dmarc.security.AuthenticatedUser;
import com.teknologiia.dmarc.service.MailboxSettingsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * The mailbox this organization collects its DMARC reports from.
 *
 * <p>Under {@code /api/admin}, so SecurityConfig restricts it to administrators.
 * Every operation is scoped to the caller's own organization — the credentials one
 * team stores are unreachable from another's session.
 */
@RestController
@RequestMapping("/api/admin/mailbox")
@RequiredArgsConstructor
public class MailboxController {

    private final MailboxSettingsService mailboxSettings;

    @GetMapping
    public MailboxSettingsResponse get(@AuthenticationPrincipal AuthenticatedUser caller) {
        return mailboxSettings.get(caller.getOrganizationId());
    }

    /** Omit the password to keep the stored one; it is never returned. */
    @PutMapping
    public MailboxSettingsResponse save(@AuthenticationPrincipal AuthenticatedUser caller,
                                        @Valid @RequestBody MailboxSettingsRequest request) {
        return mailboxSettings.save(caller.getOrganizationId(), request);
    }

    @DeleteMapping
    public Map<String, String> remove(@AuthenticationPrincipal AuthenticatedUser caller) {
        mailboxSettings.remove(caller.getOrganizationId());
        return Map.of("detail", "Mailbox settings removed. Nothing is collected automatically now.");
    }
}
