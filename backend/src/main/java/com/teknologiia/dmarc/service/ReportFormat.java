package com.teknologiia.dmarc.service;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * What a file actually is, decided from its first bytes rather than its name.
 *
 * <p>Every provider ships aggregate reports compressed, and none of them agrees on
 * how to say so. Google sends {@code .zip} holding an XML; Microsoft sends
 * {@code .gz}; others send {@code .xml.gz}, or {@code .gzip}, or a
 * {@code Content-Disposition} with no filename at all, or a filename encoded in a
 * way the mail library hands back as null.
 *
 * <p>Dispatching on the extension made every one of those a report that arrived in
 * the mailbox and never appeared in the application — with nothing anywhere saying
 * why. A file's first four bytes settle the question and no naming convention can
 * defeat them.
 *
 * <p>The name is still read, but only as a tiebreaker for content that is
 * ambiguous, and as the label in error messages.
 */
public enum ReportFormat {

    /** {@code PK..} — a zip archive, possibly holding several documents. */
    ZIP,

    /** {@code 1f 8b} — a gzip stream wrapping a single document. */
    GZIP,

    /** Text that opens like an XML document. */
    XML,

    /** Something else. Recorded rather than silently dropped. */
    UNKNOWN;

    /** Enough bytes to recognise any of the above. */
    public static final int SNIFF_BYTES = 512;

    /**
     * Identifies a document from its leading bytes.
     *
     * @param filename used only when the content is inconclusive, never in
     *                 preference to it
     */
    public static ReportFormat of(byte[] content, String filename) {
        if (content != null && content.length >= 2) {
            // PK\003\004 is a normal archive; PK\005\006 an empty one and
            // PK\007\010 a spanned one. All three start PK, which is enough.
            if (content[0] == 'P' && content[1] == 'K') {
                return ZIP;
            }
            if ((content[0] & 0xFF) == 0x1F && (content[1] & 0xFF) == 0x8B) {
                return GZIP;
            }
            if (looksLikeXml(content)) {
                return XML;
            }
        }

        // Nothing recognisable in the bytes. The name is the last resort, and it is
        // consulted here rather than first so that a mislabelled file still works.
        return fromName(filename);
    }

    /**
     * Whether the content opens like XML.
     *
     * <p>Skips a UTF-8 byte order mark and any leading whitespace: some providers
     * emit both, and a document beginning two bytes later is still a document.
     */
    private static boolean looksLikeXml(byte[] content) {
        int cursor = 0;
        if (content.length >= 3
                && (content[0] & 0xFF) == 0xEF && (content[1] & 0xFF) == 0xBB
                && (content[2] & 0xFF) == 0xBF) {
            cursor = 3;
        }
        while (cursor < content.length && Character.isWhitespace(content[cursor])) {
            cursor++;
        }
        if (cursor >= content.length || content[cursor] != '<') {
            return false;
        }

        // A lone '<' is not much. Look for the declaration or a plausible root, so
        // an HTML bounce message is not mistaken for a report.
        String head = new String(content, cursor,
                Math.min(content.length - cursor, 256), StandardCharsets.ISO_8859_1)
                .toLowerCase(Locale.ROOT);
        return head.startsWith("<?xml") || head.startsWith("<feedback");
    }

    private static ReportFormat fromName(String filename) {
        if (filename == null) {
            return UNKNOWN;
        }
        String lower = filename.toLowerCase(Locale.ROOT).trim();
        if (lower.endsWith(".zip")) {
            return ZIP;
        }
        if (lower.endsWith(".gz") || lower.endsWith(".gzip") || lower.endsWith(".tgz")) {
            return GZIP;
        }
        if (lower.endsWith(".xml")) {
            return XML;
        }
        return UNKNOWN;
    }

    /**
     * Whether a MIME type suggests a report, used to decide what is worth reading.
     *
     * <p>Deliberately generous. Reading a few extra bytes off an attachment that
     * turns out to be a signature costs nothing; skipping a report because its
     * provider labelled it {@code application/octet-stream} costs a customer their
     * data.
     */
    public static boolean mayBeReport(String contentType) {
        if (contentType == null) {
            return true;
        }
        String type = contentType.toLowerCase(Locale.ROOT);
        // The two that are never a report: the human-readable body of the message.
        return !type.startsWith("text/plain") && !type.startsWith("text/html");
    }
}
