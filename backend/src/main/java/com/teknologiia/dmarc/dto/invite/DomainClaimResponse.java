package com.teknologiia.dmarc.dto.invite;

import java.time.LocalDateTime;

/**
 * A claimed email domain and the proof still required.
 *
 * @param verification_host  DNS name the TXT record must sit under
 * @param verification_token exact value that record must contain
 */
public record DomainClaimResponse(
        Long id,
        String domain,
        Boolean verified,
        LocalDateTime verified_at,
        String default_role,
        String verification_host,
        String verification_token
) {}
