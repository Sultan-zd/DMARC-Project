package com.teknologiia.dmarc.dto.mailbox;

import com.teknologiia.dmarc.model.MailboxKind;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * A mailbox to collect reports from.
 *
 * <p>The fields that matter depend on {@code kind}, so almost nothing is
 * annotated here: bean validation cannot express “host is required, but only for
 * IMAP”, and annotating it anyway would reject a perfectly valid Microsoft
 * mailbox for want of a field it has no use for. The rules live in
 * {@code MailboxSettingsService.save}, where the kind is known and the message
 * can say which setting is missing and why.
 *
 * @param kind      IMAP, or MICROSOFT_GRAPH. Absent reads as IMAP.
 * @param username  the IMAP user, or the address of the Microsoft mailbox to read
 * @param password  the IMAP password, or the Entra ID client secret. Leave null to
 *                  keep the stored one; required the first time.
 * @param tenantId  Entra ID directory (tenant) id — Graph only
 * @param clientId  application (client) id of the registration — Graph only
 */
public record MailboxSettingsRequest(
        MailboxKind kind,
        String host,
        @Min(0) @Max(65535) int port,
        @NotBlank String username,
        String password,
        String tenantId,
        String clientId,
        boolean useSsl,
        boolean pollingEnabled
) {}
