package com.teknologiia.dmarc.dto.analysis;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * @param dkimSelector a selector to try before the guessed ones, optional.
 *
 *                     <p>DKIM selectors cannot be enumerated from DNS — the only
 *                     way to find one is to ask for a name and see whether it
 *                     answers. So a domain whose selector is not on any common list
 *                     reports "no key found" while being perfectly well signed. The
 *                     person running the analysis usually knows the name; this is
 *                     how they say it.
 *
 *                     <p>Constrained to what can legally appear in a DNS label,
 *                     because it is concatenated into a lookup name.
 */
public record DomainAnalysisRequest(
    @NotBlank String domain,

    @Size(max = 63)
    @Pattern(regexp = "^[A-Za-z0-9]([A-Za-z0-9._-]*[A-Za-z0-9])?$",
             message = "A DKIM selector may contain letters, digits, dots, hyphens and underscores.")
    String dkimSelector
) {}
