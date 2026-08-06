package com.teknologiia.dmarc.controller;

import com.teknologiia.dmarc.dto.platform.AuditPage;
import com.teknologiia.dmarc.dto.platform.BackupStatus;
import com.teknologiia.dmarc.dto.platform.PlatformOverview;
import com.teknologiia.dmarc.dto.platform.TenantDetail;
import com.teknologiia.dmarc.security.AuthenticatedUser;
import com.teknologiia.dmarc.security.PlatformAccess;
import com.teknologiia.dmarc.service.MailboxPoller;
import com.teknologiia.dmarc.service.AuditAction;
import com.teknologiia.dmarc.service.AuditService;
import com.teknologiia.dmarc.service.BackupService;
import com.teknologiia.dmarc.service.PlatformService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
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
    private final AuditService auditService;
    private final BackupService backupService;

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

    /**
     * Ends every session on any account, without touching the account itself.
     *
     * <p>The lighter of the two things an operator can do to somebody signed in.
     * Disabling locks them out until an administrator relents; this only makes them
     * sign in again — which is what you want when a laptop went missing and the
     * person still has a job to do.
     */
    @PostMapping("/accounts/{id}/revoke-sessions")
    public Map<String, String> revokeSessions(@AuthenticationPrincipal AuthenticatedUser caller,
                                              @PathVariable Long id) {
        requireOperator(caller);
        String username = platformService.revokeSessions(id, caller.getUsername());
        return Map.of("detail", "Every session held by " + username + " has been signed out.");
    }

    // ─── The audit trail ────────────────────────────────────────────

    /**
     * Who did what, queryable.
     *
     * <p>All of this is in the application log too. The difference is that a log
     * rotates and cannot be filtered by actor without reading it by hand — so
     * "who deleted this account in March" was a search through files, and only if
     * the files still existed.
     */
    @GetMapping("/audit")
    public AuditPage audit(@AuthenticationPrincipal AuthenticatedUser caller,
                           @RequestParam(required = false) String actor,
                           @RequestParam(required = false) String action,
                           @RequestParam(required = false) Integer days,
                           @RequestParam(defaultValue = "1") int page,
                           @RequestParam(name = "page_size", defaultValue = "25") int pageSize) {
        requireOperator(caller);
        return auditService.search(actor, action, days, page, pageSize);
    }

    // ─── Backups ────────────────────────────────────────────────────

    /** Where the backups stand, including how old the newest one is. */
    @GetMapping("/backups")
    public BackupStatus backups(@AuthenticationPrincipal AuthenticatedUser caller) {
        requireOperator(caller);
        return backupService.status();
    }

    /** Takes one now, without waiting for the schedule. */
    @PostMapping("/backups")
    public Map<String, String> takeBackup(@AuthenticationPrincipal AuthenticatedUser caller) {
        requireOperator(caller);
        var written = backupService.run(caller.getUsername());
        return Map.of("detail", "Backup written: " + written.getFileName());
    }

    /**
     * Downloads one.
     *
     * <p>A dump is the whole database in a single file — every password hash, every
     * encrypted mailbox credential, every tenant's reports. Getting a copy off the
     * machine is exactly what a backup is for, and it is also the largest single
     * disclosure this application can perform, so it is recorded before the bytes
     * start moving.
     */
    @GetMapping("/backups/{name}")
    public ResponseEntity<Resource> downloadBackup(@AuthenticationPrincipal AuthenticatedUser caller,
                                                   @PathVariable String name) throws IOException {
        requireOperator(caller);
        var file = backupService.find(name);

        auditService.record(caller.getUsername(), AuditAction.BACKUP_DOWNLOADED,
                AuditAction.TARGET_BACKUP, null, name,
                BackupService.humanBytes(Files.size(file)));

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(Files.size(file))
                .body(new FileSystemResource(file));
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
