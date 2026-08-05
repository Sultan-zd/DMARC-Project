package com.teknologiia.dmarc.service;

/**
 * Raised when a DMARC aggregate report cannot be parsed because it is malformed
 * or does not follow the schema defined in RFC 7489 Appendix C.
 */
public class DmarcParseException extends RuntimeException {

    public DmarcParseException(String message) {
        super(message);
    }

    public DmarcParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
