package com.teknologiia.dmarc.dto.mailbox;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * @param password leave null to keep the stored one; required when first configuring
 */
public record MailboxSettingsRequest(
        @NotBlank String host,
        @Min(1) @Max(65535) int port,
        @NotBlank String username,
        String password,
        boolean useSsl,
        boolean pollingEnabled
) {}
