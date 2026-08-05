package com.teknologiia.dmarc.controller;

import com.teknologiia.dmarc.dto.platform.PlatformOverview;
import com.teknologiia.dmarc.dto.platform.TenantDetail;
import com.teknologiia.dmarc.security.AuthenticatedUser;
import com.teknologiia.dmarc.security.PlatformAccess;
import com.teknologiia.dmarc.service.MailboxPoller;
import com.teknologiia.dmarc.service.PlatformService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * The console for whoever runs the service, as opposed to whoever runs one
 * organization inside it.
 *
 * <p>Access is checked here as well as in SecurityConfig. Two checks for one rule
 * is deliberate: this endpoint is the only place in the application that reads
 * across tenants, and a routing change that accidentally widened the security
 * matcher should not be the only thing standing in the way.
 */
@RestController
@RequestMapping("/api/platform")
@RequiredArgsConstructor
public class PlatformController {

    private final PlatformService platformService;
    private final PlatformAccess platformAccess;
    private final MailboxPoller mailboxPoller;

    @GetMapping("/overview")
    public PlatformOverview overview(@AuthenticationPrincipal AuthenticatedUser caller) {
        requireOperator(caller);
        return platformService.overview();
    }

    /** Every organization, with its accounts and what it holds. */
    @GetMapping("/organizations")
    public java.util.List<TenantDetail> organizations(@AuthenticationPrincipal AuthenticatedUser caller) {
        requireOperator(caller);
        return platformService.tenants();
    }

    @GetMapping("/organizations/{id}")
    public TenantDetail organization(@AuthenticationPrincipal AuthenticatedUser caller,
                                     @PathVariable Long id) {
        requireOperator(caller);
        return platformService.tenant(id);
    }

    /** Enables or disables any account on the platform. */
    @PatchMapping("/accounts/{id}/active")
    public Map<String, String> setAccountActive(@AuthenticationPrincipal AuthenticatedUser caller,
                                                @PathVariable Long id,
                                                @RequestBody Map<String, Boolean> body) {
        requireOperator(caller);
        boolean active = Boolean.TRUE.equals(body.get("active"));
        platformService.setAccountActive(id, active, caller.getUsername());
        return Map.of("detail", active ? "Account enabled." : "Account disabled.");
    }

    /** Removes an organization, refused unless it holds nothing at all. */
    @DeleteMapping("/organizations/{id}")
    public Map<String, String> removeOrganization(@AuthenticationPrincipal AuthenticatedUser caller,
                                                  @PathVariable Long id) {
        requireOperator(caller);
        platformService.removeEmptyOrganization(id, caller.getUsername());
        return Map.of("detail", "Empty organization removed.");
    }

    /**
     * Runs collection across every mailbox now, without waiting for the schedule.
     *
     * <p>The only action here, and it changes nothing an operator could not already
     * bring about by waiting fifteen minutes.
     */
    @PostMapping("/collect")
    public Map<String, String> collectNow(@AuthenticationPrincipal AuthenticatedUser caller) {
        requireOperator(caller);
        mailboxPoller.collect();
        return Map.of("detail", "Collection run across every configured mailbox.");
    }

    private void requireOperator(AuthenticatedUser caller) {
        if (caller == null || !platformAccess.isOperator(caller.getUsername())) {
            // 404 rather than 403: an account that is not an operator has no reason
            // to learn that this console exists.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }
}
