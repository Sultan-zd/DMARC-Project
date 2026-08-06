package com.teknologiia.dmarc.service;

import com.teknologiia.dmarc.dto.platform.AuditPage;
import com.teknologiia.dmarc.model.AuditEvent;
import com.teknologiia.dmarc.repository.AuditEventRepository;
import com.teknologiia.dmarc.web.ClientAddress;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Writes the audit trail.
 *
 * <p>Two decisions worth stating.
 *
 * <p><strong>Its own transaction.</strong> Recording runs in a new one, so an audit
 * write cannot roll back the action it describes, and — more importantly — an
 * action that later fails still leaves the attempt recorded. An audit trail that
 * only contains what succeeded is one that quietly omits the interesting half.
 *
 * <p><strong>It never throws.</strong> A failure here is logged and swallowed.
 * Refusing to delete an account because the audit table was unreachable would turn
 * a bookkeeping problem into an outage, and the application log still has the line.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    /** Used when nobody asked — the scheduled collector, a startup task. */
    public static final String SYSTEM = "system";

    private static final int MAX_PAGE_SIZE = 200;

    private final AuditEventRepository repository;
    private final ClientAddress clientAddress;

    public void record(String actor, String action, String targetType,
                       Long targetId, String targetLabel, String detail) {
        record(actor, null, action, targetType, targetId, targetLabel, detail);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String actor, Long actorOrganizationId, String action, String targetType,
                       Long targetId, String targetLabel, String detail) {
        try {
            repository.save(AuditEvent.builder()
                    .actor(actor == null || actor.isBlank() ? SYSTEM : actor)
                    .actorOrganizationId(actorOrganizationId)
                    .action(action)
                    .targetType(targetType)
                    .targetId(targetId)
                    .targetLabel(trim(targetLabel, 255))
                    .detail(trim(detail, 500))
                    .clientIp(currentClientIp())
                    .build());
        } catch (Exception e) {
            // Deliberately swallowed — see the class comment.
            log.error("Could not record audit event {} by {}: {}", action, actor, e.getMessage());
        }
    }

    /**
     * Reads the trail back.
     *
     * <p>Not scoped to an organization: this is the operator's view, and the whole
     * point of it is to answer questions that cross tenants — who removed that
     * organization, who revealed a credential column. The endpoint in front of it
     * is the one that decides who may ask.
     *
     * @param days how far back to look, or null for everything
     */
    @Transactional(readOnly = true)
    public AuditPage search(String actor, String action, Integer days, int page, int pageSize) {
        int size = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        LocalDateTime since = days == null || days <= 0
                ? null
                : LocalDateTime.now(ZoneOffset.UTC).minusDays(days);

        Page<AuditEvent> found = repository.search(
                blankToNull(actor), blankToNull(action), since,
                PageRequest.of(Math.max(page - 1, 0), size));

        List<AuditPage.Entry> entries = found.getContent().stream()
                .map(e -> new AuditPage.Entry(e.getId(), e.getAt(), e.getActor(), e.getAction(),
                        e.getTargetType(), e.getTargetId(), e.getTargetLabel(),
                        e.getDetail(), e.getClientIp()))
                .toList();

        return new AuditPage(entries, repository.distinctActions(),
                found.getTotalElements(), Math.max(page, 1), size);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * The caller's address, when there is a caller.
     *
     * <p>Read from the current request rather than passed down through every
     * signature: the scheduled collector and the startup initialiser have no
     * request, and threading a nullable address through six services to serve two
     * of them is worse than looking it up here. Returns null outside a request.
     */
    private String currentClientIp() {
        if (RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes attributes) {
            try {
                return clientAddress.of(attributes.getRequest());
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
