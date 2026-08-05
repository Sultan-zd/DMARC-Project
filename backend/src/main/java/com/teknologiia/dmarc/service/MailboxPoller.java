package com.teknologiia.dmarc.service;

import com.teknologiia.dmarc.model.MailboxSettings;
import com.teknologiia.dmarc.repository.MailboxSettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Visits every configured mailbox on a schedule.
 *
 * <p>This did not exist. The interval was configurable, the Administration screen
 * said "checked every 15 minutes", and nothing ever ran — reports only arrived when
 * somebody remembered to press a button. A DMARC dashboard nobody presses buttons on
 * is a DMARC dashboard that quietly stops reflecting reality.
 *
 * <p>Each organization is polled independently and a failure is contained: one
 * unreachable mailbox must not stop the others being collected.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MailboxPoller {

    private final MailboxSettingsRepository repository;
    private final EmailService emailService;

    @Scheduled(
            // First run is delayed so a restart does not open every IMAP connection
            // at once while the application is still warming up.
            initialDelayString = "${app.imap.initial-delay-ms:60000}",
            fixedDelayString = "#{${app.imap.polling-interval-minutes:15} * 60 * 1000}")
    public void collect() {
        List<MailboxSettings> mailboxes = repository.findByPollingEnabledTrue();
        if (mailboxes.isEmpty()) {
            return;
        }

        log.info("Scheduled collection across {} mailbox(es)", mailboxes.size());
        for (MailboxSettings mailbox : mailboxes) {
            Long organizationId = mailbox.getOrganization().getId();
            try {
                var result = emailService.fetchAndProcessEmails(organizationId);
                if (result.reportsStored() > 0) {
                    log.info("Organization {}: imported {} report(s)",
                            organizationId, result.reportsStored());
                }
            } catch (Exception e) {
                // fetchAndProcessEmails already records the failure against the
                // mailbox; this only stops it ending the loop.
                log.error("Scheduled collection failed for organization {}", organizationId, e);
            }
        }
    }
}
