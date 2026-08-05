package com.teknologiia.dmarc.web;

import java.net.IDN;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Normalises and validates a domain name submitted for analysis.
 *
 * <p>The public scan endpoint turns its input into outbound DNS queries, so the
 * input has to be constrained before it reaches a resolver: anything that is not
 * a public, resolvable hostname is rejected here rather than looked up.
 *
 * <p>Input is also forgiving in one direction — people paste URLs rather than
 * bare hostnames, so a scheme, path, port or userinfo is stripped instead of
 * refused.
 */
public final class DomainNameValidator {

    /** Longest legal fully-qualified domain name. */
    private static final int MAX_LENGTH = 253;
    private static final int MAX_LABEL_LENGTH = 63;

    private static final Pattern LABEL = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$");
    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    /**
     * Suffixes that never resolve publicly. Scanning them cannot produce a useful
     * answer, and accepting them invites the endpoint being pointed at internal
     * infrastructure.
     */
    private static final Set<String> BLOCKED_SUFFIXES = Set.of(
            "localhost", "local", "internal", "intranet", "private",
            "corp", "home", "lan", "test", "example", "invalid", "onion"
    );

    private DomainNameValidator() {
    }

    /**
     * @return the normalised, lowercase, ASCII form of the domain
     * @throws InvalidDomainException if the input is not a public hostname
     */
    public static String normalise(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidDomainException("Enter a domain name.");
        }

        String value = raw.trim().toLowerCase(Locale.ROOT);

        // Accept pasted URLs: drop scheme, userinfo, port, path, query and fragment.
        value = value.replaceFirst("^[a-z][a-z0-9+.-]*://", "");
        int at = value.indexOf('@');
        if (at >= 0) {
            value = value.substring(at + 1);
        }
        value = value.split("[/?#]", 2)[0];
        int colon = value.indexOf(':');
        if (colon >= 0) {
            value = value.substring(0, colon);
        }

        // A trailing dot is a legal FQDN root marker but not wanted downstream.
        while (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }

        if (value.isEmpty()) {
            throw new InvalidDomainException("Enter a domain name.");
        }

        // Internationalised names are converted to punycode before validation.
        try {
            value = IDN.toASCII(value, IDN.ALLOW_UNASSIGNED);
        } catch (IllegalArgumentException e) {
            throw new InvalidDomainException("That does not look like a valid domain name.");
        }

        if (value.length() > MAX_LENGTH) {
            throw new InvalidDomainException("That domain name is too long.");
        }
        if (IPV4.matcher(value).matches() || value.contains(":")) {
            throw new InvalidDomainException("Enter a domain name, not an IP address.");
        }

        String[] labels = value.split("\\.");
        if (labels.length < 2) {
            throw new InvalidDomainException(
                    "Enter a full domain name, for example teknologiia.com.");
        }

        for (String label : labels) {
            if (label.isEmpty() || label.length() > MAX_LABEL_LENGTH || !LABEL.matcher(label).matches()) {
                throw new InvalidDomainException("That does not look like a valid domain name.");
            }
        }

        String tld = labels[labels.length - 1];
        if (BLOCKED_SUFFIXES.contains(tld)) {
            throw new InvalidDomainException("Only publicly resolvable domains can be analysed.");
        }
        if (tld.length() < 2 || tld.chars().anyMatch(Character::isDigit)) {
            throw new InvalidDomainException("That does not look like a valid domain name.");
        }

        return value;
    }

    /** Raised when a submitted domain cannot be analysed. Carries a user-facing message. */
    public static class InvalidDomainException extends RuntimeException {
        public InvalidDomainException(String message) {
            super(message);
        }
    }
}
