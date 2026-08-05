package com.teknologiia.dmarc.service;

import com.teknologiia.dmarc.dto.ingest.IngestionResult;
import com.teknologiia.dmarc.model.MailboxSettings;
import com.teknologiia.dmarc.model.Organization;
import com.teknologiia.dmarc.repository.OrganizationRepository;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.ReceivedDateTerm;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Collects DMARC aggregate reports from the mailbox they are sent to.
 *
 * <p>Reporting providers deliver aggregate reports as mail attachments, so a run
 * connects over IMAP, walks unread messages, and hands every {@code .xml},
 * {@code .gz} or {@code .zip} attachment to {@link ReportIngestionService}.
 *
 * <p>The mailbox belongs to the organization, not to the server. It used to be a
 * single set of credentials in configuration, which meant every tenant's run read
 * the same inbox and whoever pressed the button took ownership of what was in it —
 * and an aggregate report names every IP address sending as the domains it covers.
 *
 * <p>The mailbox is opened <strong>read-only</strong> and no message is ever
 * modified. An earlier version selected unread mail and flagged each processed
 * message as seen, which had two consequences nobody asked for: pointed at a real
 * inbox it marked hundreds of unrelated personal messages as read, and because
 * IMAP returns matches oldest-first, a report arriving behind a backlog of unread
 * mail was never reached at all. Which reports have already been taken is decided
 * by the report id recorded in the database, not by a flag in somebody's mailbox.
 *
 * <p>With no mailbox configured the run says so plainly rather than returning a
 * success it did not achieve; uploading files is the supported path in that case.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    /** Bounds how many messages a single run will examine. */
    private static final int MAX_MESSAGES_PER_RUN = 200;

    /** How far back the very first collection reaches. */
    private static final int FIRST_RUN_LOOKBACK_DAYS = 30;

    private final ReportIngestionService ingestionService;
    private final OrganizationRepository organizationRepository;
    private final MailboxSettingsService mailboxSettings;

    /**
     * @param organizationId tenant that will own everything this run imports
     */
    public IngestionResult fetchAndProcessEmails(Long organizationId) {
        var configured = mailboxSettings.forOrganization(organizationId);
        if (configured.isEmpty()) {
            return new IngestionResult(0, 0, 0, 0, List.of(
                    "No mailbox is configured for this organization. Add one under "
                            + "Administration, or upload report files directly."));
        }
        MailboxSettings mailbox = configured.get();

        Store store = null;
        Folder inbox = null;

        try {
            store = connect(mailbox);
            inbox = store.getFolder("INBOX");
            // Read-only: this is somebody's mailbox, and collecting from it must not
            // change what they see in it.
            inbox.open(Folder.READ_ONLY);

            // Everything since shortly before the last successful run. Overlapping
            // deliberately — a report that arrived while the previous run was in
            // flight would otherwise fall between two windows — and duplicates cost
            // nothing, because ingest() recognises a report id it already holds.
            Date since = lookBackFrom(mailbox);
            Message[] messages = inbox.search(new ReceivedDateTerm(ComparisonTerm.GE, since));

            // Newest first: a report delivered today must not sit behind a year of
            // older mail waiting for its turn.
            int considered = Math.min(messages.length, MAX_MESSAGES_PER_RUN);
            log.info("IMAP run: {} message(s) since {}, examining the {} most recent",
                    messages.length, since, considered);

            Organization organization = organizationRepository.getReferenceById(organizationId);
            IngestionResult result = IngestionResult.empty();
            for (int i = messages.length - 1; i >= messages.length - considered; i--) {
                result = result.merge(processMessage(organization, messages[i]));
            }
            mailboxSettings.recordRun(organizationId, true,
                    result.reportsStored() + " report(s) imported, "
                            + result.duplicatesSkipped() + " already known");
            return result;

        } catch (Exception e) {
            log.error("IMAP ingestion failed for organization {}", organizationId, e);
            mailboxSettings.recordRun(organizationId, false, e.getMessage());
            return new IngestionResult(0, 0, 0, 0,
                    List.of("Could not read the mailbox: " + e.getMessage()));
        } finally {
            closeQuietly(inbox, store);
        }
    }

    /**
     * How far back a run looks.
     *
     * <p>From a day before the last successful run, so nothing slips between two
     * windows. A mailbox that has never been collected from reaches back further,
     * to pick up whatever is already waiting.
     */
    private Date lookBackFrom(MailboxSettings mailbox) {
        LocalDateTime from = mailbox.getLastRunAt() != null && Boolean.TRUE.equals(mailbox.getLastRunOk())
                ? mailbox.getLastRunAt().minusDays(1)
                : LocalDateTime.now(ZoneOffset.UTC).minusDays(FIRST_RUN_LOOKBACK_DAYS);
        return Date.from(from.toInstant(ZoneOffset.UTC));
    }

    private Store connect(MailboxSettings mailbox) throws Exception {
        String host = mailbox.getHost();
        int port = mailbox.getPort();
        String username = mailbox.getUsername();
        String password = mailboxSettings.password(mailbox);
        boolean useSsl = mailbox.isUseSsl();

        Properties properties = new Properties();
        String protocol = useSsl ? "imaps" : "imap";

        properties.put("mail.store.protocol", protocol);
        properties.put("mail." + protocol + ".host", host);
        properties.put("mail." + protocol + ".port", String.valueOf(port));
        properties.put("mail." + protocol + ".connectiontimeout", "15000");
        properties.put("mail." + protocol + ".timeout", "30000");
        if (useSsl) {
            properties.put("mail.imaps.ssl.enable", "true");
        } else {
            properties.put("mail.imap.starttls.enable", "true");
        }

        Store store = Session.getInstance(properties).getStore(protocol);
        store.connect(host, port, username, password);
        return store;
    }

    private IngestionResult processMessage(Organization organization, Message message) {
        IngestionResult result = IngestionResult.empty();
        try {
            for (Part attachment : collectAttachments(message)) {
                try (InputStream content = attachment.getInputStream()) {
                    result = result.merge(ingestionService.ingest(organization, attachment.getFileName(), content));
                }
            }
        } catch (Exception e) {
            log.warn("Skipping unreadable message: {}", e.getMessage());
            result = result.merge(new IngestionResult(0, 0, 0, 0,
                    List.of("A message could not be read: " + e.getMessage())));
        }
        return result;
    }

    /** Walks the MIME tree and returns the parts that look like DMARC report attachments. */
    private List<Part> collectAttachments(Part part) throws Exception {
        List<Part> attachments = new ArrayList<>();

        if (part.getContent() instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                attachments.addAll(collectAttachments(multipart.getBodyPart(i)));
            }
            return attachments;
        }

        String filename = part.getFileName();
        if (filename != null) {
            String lower = filename.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".xml") || lower.endsWith(".gz") || lower.endsWith(".zip")) {
                attachments.add(part);
            }
        }
        return attachments;
    }

    private void closeQuietly(Folder inbox, Store store) {
        try {
            if (inbox != null && inbox.isOpen()) {
                inbox.close(false);
            }
        } catch (Exception e) {
            log.debug("Could not close the mailbox folder", e);
        }
        try {
            if (store != null && store.isConnected()) {
                store.close();
            }
        } catch (Exception e) {
            log.debug("Could not close the mailbox connection", e);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
