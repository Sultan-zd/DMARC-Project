package com.teknologiia.dmarc.service;

/**
 * The vocabulary of the audit trail.
 *
 * <p>Constants rather than an enum: the column stores text, and a value written by
 * a version of the application that has since been changed must still read back
 * rather than fail deserialisation. An audit trail that cannot be read after an
 * upgrade is not one.
 */
public final class AuditAction {

    private AuditAction() {}

    // ── Accounts ──
    public static final String ACCOUNT_CREATED = "ACCOUNT_CREATED";
    public static final String ACCOUNT_DELETED = "ACCOUNT_DELETED";
    public static final String ACCOUNT_ENABLED = "ACCOUNT_ENABLED";
    public static final String ACCOUNT_DISABLED = "ACCOUNT_DISABLED";
    public static final String ACCOUNT_ROLE_CHANGED = "ACCOUNT_ROLE_CHANGED";
    public static final String ACCOUNT_PASSWORD_RESET_BY_ADMIN = "ACCOUNT_PASSWORD_RESET_BY_ADMIN";

    // ── Credentials and sessions ──
    public static final String PASSWORD_CHANGED = "PASSWORD_CHANGED";
    public static final String PASSWORD_RESET_COMPLETED = "PASSWORD_RESET_COMPLETED";
    public static final String SESSIONS_REVOKED = "SESSIONS_REVOKED";
    public static final String SIGN_IN_FAILED = "SIGN_IN_FAILED";
    public static final String TWO_FACTOR_ENABLED = "TWO_FACTOR_ENABLED";
    public static final String TWO_FACTOR_DISABLED = "TWO_FACTOR_DISABLED";

    // ── Organizations ──
    public static final String ORGANIZATION_REMOVED = "ORGANIZATION_REMOVED";

    // ── The operator's database console, which can destroy anything ──
    public static final String DATABASE_ROW_DELETED = "DATABASE_ROW_DELETED";
    public static final String DATABASE_TABLE_CLEARED = "DATABASE_TABLE_CLEARED";
    public static final String DATABASE_SECRETS_REVEALED = "DATABASE_SECRETS_REVEALED";

    // ── Report intake ──
    public static final String MAILBOX_SAVED = "MAILBOX_SAVED";
    public static final String MAILBOX_DELETED = "MAILBOX_DELETED";

    /** Target kinds, so the console can group and filter without parsing labels. */
    public static final String TARGET_ACCOUNT = "ACCOUNT";
    public static final String TARGET_ORGANIZATION = "ORGANIZATION";
    public static final String TARGET_TABLE = "TABLE";
    public static final String TARGET_MAILBOX = "MAILBOX";
}
