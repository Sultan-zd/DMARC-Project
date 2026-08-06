package com.teknologiia.dmarc.service;

import com.teknologiia.dmarc.model.User;
import com.teknologiia.dmarc.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Ending sessions that have already been handed out.
 *
 * <p>A JWT is stateless by design: once signed it is valid until it expires, and
 * nothing in the token can be changed afterwards. That is what makes it cheap —
 * no lookup per request — and it is also why a stolen one used to be good for a
 * full hour with no way to stop it.
 *
 * <p>The fix here is a watermark per account rather than a table of live sessions.
 * Every issued token carries the instant it was minted; the account carries the
 * instant its sessions were last invalidated; the authentication filter refuses
 * anything older. One nullable column, one comparison, and the filter was already
 * loading the account for other reasons.
 *
 * <p>What this deliberately does not give: ending <em>one</em> session while
 * leaving the others. That needs a record per sign-in, which is a real feature
 * with real storage — and the thing actually being asked for, when somebody says a
 * laptop was stolen, is all of them.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionService {

    private final UserRepository userRepository;
    private final AuditService auditService;

    /**
     * Invalidates every session this account currently holds.
     *
     * @param actor who is doing it — the account itself, an administrator, or an
     *              operator. Recorded, because forcing somebody out is exactly the
     *              kind of act that should be answerable later.
     * @param reason short, appears in the audit trail
     * @return the watermark, so a caller that wants to keep the current session
     *         alive can mint a replacement token dated after it
     */
    @Transactional
    public LocalDateTime revokeAll(String username, String actor, String reason) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found."));
        return revokeAll(user, actor, reason);
    }

    @Transactional
    public LocalDateTime revokeAll(User user, String actor, String reason) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        user.setTokensValidFrom(now);
        userRepository.save(user);

        auditService.record(actor, AuditAction.SESSIONS_REVOKED, AuditAction.TARGET_ACCOUNT,
                user.getId(), user.getUsername(), reason);

        log.info("Sessions revoked for {} by {} — {}", user.getUsername(), actor, reason);
        return now;
    }
}
