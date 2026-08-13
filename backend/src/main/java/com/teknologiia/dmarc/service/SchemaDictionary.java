package com.teknologiia.dmarc.service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What the schema means, in words.
 *
 * <p>A database client can tell you that {@code users.must_change_password} is a
 * {@code bit(1)} that is currently 1 on three rows. It cannot tell you that those
 * three accounts were created by an administrator, are being held at a change screen,
 * and that the number staying high is a sign invitations are going unread. That
 * second kind of knowledge lives here.
 *
 * <p>Anything absent falls back to a name derived from the column, so a table added
 * later still lists and still reads — it simply arrives undescribed rather than
 * breaking the page. The fallback is deliberately visible in the interface, which is
 * what prompts someone to come and write the missing line.
 */
public final class SchemaDictionary {

    private SchemaDictionary() {}

    /** The three things this application actually stores. */
    public static final String ACCESS = "Accounts and access";
    public static final String EMAIL = "Email authentication data";
    public static final String OPERATIONS = "Operations";
    public static final String AUDIT = "Accountability";

    /**
     * The order the groups are listed in — who can get in, what was collected, how
     * it is running. Alphabetical order put "Operations" first and "Accounts" last,
     * which is the reverse of what anyone opens the page to look at.
     */
    private static final List<String> GROUP_ORDER = List.of(ACCESS, EMAIL, OPERATIONS, AUDIT);

    public static int groupRank(String group) {
        int rank = GROUP_ORDER.indexOf(group);
        return rank < 0 ? GROUP_ORDER.size() : rank;
    }

    public record TableDoc(String label, String group, String description) {}

    private static final TableDoc UNKNOWN =
            new TableDoc(null, "Other", "Not yet described. Added to the schema after this "
                    + "dictionary was written.");

    private static final Map<String, TableDoc> TABLES = Map.ofEntries(
            Map.entry("users", new TableDoc("Accounts", ACCESS,
                    "Everyone who can sign in. Each one belongs to exactly one organization, "
                            + "and that link is what every other query is filtered by.")),

            Map.entry("organizations", new TableDoc("Organizations", ACCESS,
                    "The tenants. Reports, analyses, alerts and mailbox credentials are all "
                            + "separated along this table — deleting a row here takes everything "
                            + "that pointed at it.")),

            Map.entry("organization_domains", new TableDoc("Claimed email domains", ACCESS,
                    "Domains an organization has proved it owns, by publishing a TXT record. "
                            + "Once verified, anyone signing up with an address there joins that "
                            + "organization instead of creating a second one under the same name.")),

            Map.entry("invitations", new TableDoc("Invitations", ACCESS,
                    "Single-use links emailed to colleagues. A row survives being accepted, so "
                            + "this doubles as the record of who let whom in.")),

            Map.entry("email_verification_tokens", new TableDoc("Sign-up confirmations", ACCESS,
                    "The link sent when an account is created. Until it is redeemed the account "
                            + "exists but is inactive and cannot sign in.")),

            Map.entry("password_reset_tokens", new TableDoc("Password reset links", ACCESS,
                    "Emailed to whoever asks to reset a password. Kept in their own table "
                            + "rather than reusing sign-up confirmations: a link that only "
                            + "confirms an address must never be redeemable for a new password, "
                            + "or a 24-hour sign-up link becomes a 24-hour account takeover.")),

            Map.entry("recovery_codes", new TableDoc("Two-step recovery codes", ACCESS,
                    "Ten single-use codes issued when two-step verification is switched on — the "
                            + "way back in when the authenticator is on a lost phone. Stored "
                            + "hashed, so a used one can be recognised but none can be read back.")),

            Map.entry("dmarc_reports", new TableDoc("Aggregate reports", EMAIL,
                    "One XML report as a mailbox provider sent it: who reported, which domain, "
                            + "over what period, and the policy that was in force at the time.")),

            Map.entry("dmarc_records", new TableDoc("Sending sources", EMAIL,
                    "The body of each report — one row per source address that sent mail as the "
                            + "domain, with how many messages and whether SPF and DKIM passed. "
                            + "This is the largest table and the one that answers who is spoofing "
                            + "you.")),

            Map.entry("domain_analyses", new TableDoc("Domain analyses", EMAIL,
                    "Every DNS check that was run, with its score out of 100 and the full "
                            + "findings kept as JSON so a past result can be reopened exactly as "
                            + "it was seen.")),

            Map.entry("mailbox_settings", new TableDoc("Report mailboxes", OPERATIONS,
                    "The IMAP mailbox each organization has reports collected from, one per "
                            + "organization. Holds the only reversible secret in the schema.")),

            Map.entry("audit_events", new TableDoc("Audit trail", AUDIT,
                    "Who did what, and when. Everything here is in the application log as "
                            + "well — the difference is that a log rotates and cannot be "
                            + "filtered by actor without reading it by hand. Nothing in the "
                            + "application updates a row here; it is written once and left.")),

            Map.entry("alerts", new TableDoc("Alerts", OPERATIONS,
                    "Findings raised for an organization: volume spikes, failure rates, and "
                            + "problems an analysis turned up. Regenerated from the data, so this "
                            + "is the one table that can be emptied without losing anything."))
    );

    /**
     * Column meanings, keyed {@code table.column}.
     *
     * <p>Written for the person reading a row and wondering whether what they see is
     * normal — so a description says what an unexpected value implies, not merely
     * what the field is called in longer words.
     */
    private static final Map<String, String> COLUMNS = Map.ofEntries(
            // ── users ──
            Map.entry("users.id", "Surrogate key. Recovery codes and sign-up confirmations point here."),
            Map.entry("users.organization_id", "The tenant this account belongs to. Every read this "
                    + "account makes is filtered by it, which is what keeps organizations apart."),
            Map.entry("users.username", "What they type to sign in. Unique across the whole platform, "
                    + "not merely within their organization."),
            Map.entry("users.email", "Where confirmation, invitation and alert mail goes. Also how a "
                    + "claimed domain decides which organization a sign-up joins."),
            Map.entry("users.hashed_password", "BCrypt hash. The password itself is never stored and "
                    + "cannot be recovered from this — only replaced."),
            Map.entry("users.role", "ADMIN, ANALYST or VIEWER. Enforced at the API, so changing it "
                    + "here genuinely changes what the account can do."),
            Map.entry("users.is_active", "A disabled account keeps all of its data but cannot sign "
                    + "in. Also the sign-up flag: a new account stays inactive until its "
                    + "confirmation link is followed."),
            Map.entry("users.must_change_password", "Set when an administrator chose the password. "
                    + "It travelled through a channel nobody controls, so the account is held at a "
                    + "change screen until the user picks their own."),
            Map.entry("users.totp_secret", "The shared seed for their authenticator app, in the "
                    + "clear — anyone who reads it can generate that account's codes. Present as "
                    + "soon as enrolment starts, which is why it alone does not mean two-step is on."),
            Map.entry("users.totp_enabled_at", "When two-step verification was confirmed. Null means "
                    + "it is not in force, whatever the secret column holds."),
            Map.entry("users.tokens_valid_from", "Sessions opened before this instant are no "
                    + "longer honoured. A JWT cannot be called back once signed, so revoking is "
                    + "done by refusing anything older than this watermark. Moved by a password "
                    + "change, a reset, a disable, or somebody signing out everywhere. Empty "
                    + "means nothing has ever been revoked."),
            Map.entry("users.created_at", "When the account was created, in UTC."),

            // ── organizations ──
            Map.entry("organizations.id", "What every tenant-scoped table points at."),
            Map.entry("organizations.name", "As typed at sign-up. Not unique — two unrelated "
                    + "companies can register the same name, which is why the claimed-domain "
                    + "mechanism exists."),
            Map.entry("organizations.created_at", "When the first account here signed up."),

            // ── organization_domains ──
            Map.entry("organization_domains.domain", "The domain being claimed. Unique platform-wide: "
                    + "two organizations cannot both own example.com."),
            Map.entry("organization_domains.verification_token", "The value that must appear in a TXT "
                    + "record on the domain. Proof of control, not a credential — it is meant to be "
                    + "published."),
            Map.entry("organization_domains.verified_at", "When the TXT record was last seen. Null "
                    + "means the claim is pending and grants nothing."),
            Map.entry("organization_domains.default_role", "The role given to accounts that join "
                    + "through this domain. Worth keeping at VIEWER unless there is a reason not to."),
            Map.entry("organization_domains.created_at", "When the claim was started."),

            // ── invitations ──
            Map.entry("invitations.email", "Who the link was sent to. It only works for this address."),
            Map.entry("invitations.role", "The role the account will be created with."),
            Map.entry("invitations.token", "The secret in the emailed link. Anyone holding it can "
                    + "join this organization until it expires."),
            Map.entry("invitations.invited_by", "The administrator who sent it."),
            Map.entry("invitations.expires_at", "Seven days after sending. Past this the link is "
                    + "refused even though the row remains."),
            Map.entry("invitations.accepted_at", "When it was used. Null and unexpired means the "
                    + "invitation is still outstanding — a pile of these is usually mail not arriving."),
            Map.entry("invitations.created_at", "When it was sent."),

            // ── email_verification_tokens ──
            Map.entry("email_verification_tokens.user_id", "The account waiting to be activated."),
            Map.entry("email_verification_tokens.token", "The secret in the confirmation link."),
            Map.entry("email_verification_tokens.expires_at", "After this the link is dead and the "
                    + "account needs a new one."),
            Map.entry("email_verification_tokens.used_at", "When the link was followed. Set once and "
                    + "never again — a second attempt is refused, which is how a forwarded link "
                    + "cannot activate someone else."),
            Map.entry("email_verification_tokens.created_at", "When the link was issued."),

            // ── password_reset_tokens ──
            Map.entry("password_reset_tokens.user_id", "The account whose password this link "
                    + "would change."),
            Map.entry("password_reset_tokens.token", "The secret in the emailed link. Anyone "
                    + "holding it can set a new password on that account without knowing the "
                    + "old one — which is the point, and why it lasts an hour rather than a day."),
            Map.entry("password_reset_tokens.expires_at", "One hour after issue by default. Past "
                    + "it the link is refused even though the row remains."),
            Map.entry("password_reset_tokens.used_at", "When it was spent — either by being "
                    + "redeemed, or by a newer request superseding it. Asking for a fresh link is "
                    + "how somebody reacts to losing the device the old one is sitting on, so it "
                    + "has to take the old one away."),
            Map.entry("password_reset_tokens.created_at", "When the link was issued. A run of "
                    + "these against one account, none of them used, is somebody else asking."),

            // ── recovery_codes ──
            Map.entry("recovery_codes.user_id", "Whose codes these are."),
            Map.entry("recovery_codes.code_hash", "Hashed, like a password. The codes were shown once "
                    + "at enrolment and cannot be shown again."),
            Map.entry("recovery_codes.used_at", "When this code was spent. Ten unused rows is a "
                    + "fresh enrolment; zero is an account one lost phone away from being locked out."),

            // ── mailbox_settings ──
            Map.entry("mailbox_settings.organization_id", "One mailbox per organization — the unique "
                    + "constraint is here, not merely an index."),
            Map.entry("mailbox_settings.kind", "How this mailbox is reached: IMAP, or "
                    + "MICROSOFT_GRAPH. Microsoft removed Basic authentication from Exchange "
                    + "Online, so a Microsoft 365 mailbox cannot be opened over IMAP by any "
                    + "password at all and is read as a registered application instead. Empty on "
                    + "rows written before Graph support existed; those are all IMAP."),
            Map.entry("mailbox_settings.host", "IMAP server — imap.gmail.com for Gmail. Reads "
                    + "graph.microsoft.com for a Graph mailbox, where it is not configurable."),
            Map.entry("mailbox_settings.port", "993 for IMAP over SSL, which is what this should be. "
                    + "443 for Graph, which speaks ordinary HTTPS."),
            Map.entry("mailbox_settings.username", "The mailbox the DMARC record's rua= address "
                    + "delivers to. For Graph it is the full address, which is what identifies the "
                    + "mailbox in the API call."),
            Map.entry("mailbox_settings.password_cipher", "The one secret this mailbox needs, "
                    + "AES-GCM encrypted rather than hashed because it has to be presented again on "
                    + "every run: an IMAP app password, or the client secret of the Entra ID "
                    + "registration. Unreadable without SECRETS_KEY, and the application refuses to "
                    + "store one at all when that key is unset."),
            Map.entry("mailbox_settings.tenant_id", "Entra ID directory the application registration "
                    + "lives in. Graph only; empty for IMAP."),
            Map.entry("mailbox_settings.client_id", "The Entra ID application registration reading "
                    + "the mailbox. Not a secret — it identifies the application, it does not "
                    + "authenticate it. Graph only."),
            Map.entry("mailbox_settings.use_ssl", "Turning this off sends the password in the clear. "
                    + "Always true for Graph, which has no unencrypted mode."),
            Map.entry("mailbox_settings.polling_enabled", "Whether the scheduled collection includes "
                    + "this mailbox. Off means reports only arrive when someone presses collect."),
            Map.entry("mailbox_settings.last_run_at", "When collection last attempted this mailbox."),
            Map.entry("mailbox_settings.last_run_ok", "False means that organization is receiving "
                    + "nothing and does not necessarily know it."),
            Map.entry("mailbox_settings.last_run_summary", "What the last run found, or the error it "
                    + "hit. The first place to look when a tenant says reports stopped."),

            // ── dmarc_reports ──
            Map.entry("dmarc_reports.organization_id", "Which tenant this report belongs to."),
            Map.entry("dmarc_reports.report_id", "The provider's own identifier for the report, from "
                    + "the XML. Used to recognise a report already collected rather than storing it "
                    + "twice."),
            Map.entry("dmarc_reports.org_name", "Who sent the report — Google, Microsoft, Yahoo. Not "
                    + "your organization."),
            Map.entry("dmarc_reports.org_email", "The reporting provider's contact address."),
            Map.entry("dmarc_reports.domain", "The domain the report is about."),
            Map.entry("dmarc_reports.date_begin", "Start of the period covered, usually 24 hours."),
            Map.entry("dmarc_reports.date_end", "End of the period covered."),
            Map.entry("dmarc_reports.policy", "The p= value the provider saw in your DMARC record: "
                    + "none, quarantine or reject. This is what was actually published at the time, "
                    + "which may not be what is published now."),
            Map.entry("dmarc_reports.sp_policy", "The policy for subdomains, if one was set separately."),
            Map.entry("dmarc_reports.adkim", "DKIM alignment: s for strict, r for relaxed."),
            Map.entry("dmarc_reports.aspf", "SPF alignment: s for strict, r for relaxed."),
            Map.entry("dmarc_reports.pct", "The percentage of mail the policy was applied to. Below "
                    + "100 means the policy was only partly in force."),
            Map.entry("dmarc_reports.created_at", "When this report was collected, not when it was "
                    + "sent — the gap between this and date_end is your collection delay."),

            // ── dmarc_records ──
            Map.entry("dmarc_records.report_id", "The report this row came out of."),
            Map.entry("dmarc_records.source_ip", "The address that actually sent the mail. An "
                    + "unfamiliar one with a high count and failing authentication is the thing "
                    + "this whole product exists to surface."),
            Map.entry("dmarc_records.count", "How many messages this source sent in the period."),
            Map.entry("dmarc_records.disposition", "What the receiver did: none, quarantine or "
                    + "reject. Only meaningful once your policy is past none."),
            Map.entry("dmarc_records.header_from", "The domain the recipient saw in the From line — "
                    + "the one being protected."),
            Map.entry("dmarc_records.envelope_from", "The domain used at the SMTP envelope, which the "
                    + "recipient never sees. It differing from header_from is normal for forwarders "
                    + "and mailing lists."),
            Map.entry("dmarc_records.spf_domain", "The domain SPF was checked against."),
            Map.entry("dmarc_records.spf_result", "pass, fail, softfail, neutral, none or "
                    + "permerror. Anything but pass means SPF did not vouch for this source."),
            Map.entry("dmarc_records.dkim_domain", "The domain that signed the message."),
            Map.entry("dmarc_records.dkim_selector", "Which key was used. This is the only place "
                    + "selectors can be learned from, since DNS gives no way to list them."),
            Map.entry("dmarc_records.dkim_result", "pass or fail. A source can fail DKIM and still "
                    + "pass DMARC if SPF aligned."),

            // ── domain_analyses ──
            Map.entry("domain_analyses.organization_id", "Null for an anonymous scan from the landing "
                    + "page — those belong to nobody and are what the public counter counts."),
            Map.entry("domain_analyses.domain", "The domain that was checked."),
            Map.entry("domain_analyses.score", "Out of 100: DMARC 40, SPF 30, DKIM 20, MX 10."),
            Map.entry("domain_analyses.grade", "A+ down to F, from the score."),
            Map.entry("domain_analyses.analyzed_at", "When the check ran. DNS changes, so an old "
                    + "result describes an old configuration."),
            Map.entry("domain_analyses.analyzed_by", "The account that ran it, or blank for an "
                    + "anonymous scan."),
            Map.entry("domain_analyses.results_json", "The full findings per control, as they were "
                    + "seen. Kept so a past result can be reopened rather than re-run."),
            Map.entry("domain_analyses.score_breakdown_json", "Which points were awarded and which "
                    + "were withheld, control by control."),
            Map.entry("domain_analyses.recommendations_json", "What to fix, in the order it was "
                    + "worth fixing."),

            // ── audit_events ──
            Map.entry("audit_events.at", "When it happened, in UTC."),
            Map.entry("audit_events.actor", "Who did it, by username. `system` for anything "
                    + "nobody asked for — the scheduled collector, a startup task."),
            Map.entry("audit_events.actor_organization_id", "The actor's organization, or empty "
                    + "when an operator acted across all of them."),
            Map.entry("audit_events.action", "What was done. ACCOUNT_DELETED, SESSIONS_REVOKED, "
                    + "DATABASE_TABLE_CLEARED and the rest."),
            Map.entry("audit_events.target_type", "What kind of thing it was done to: ACCOUNT, "
                    + "ORGANIZATION, TABLE, MAILBOX."),
            Map.entry("audit_events.target_id", "The target's key, where it had one. Often "
                    + "points at a row that no longer exists — which is the point."),
            Map.entry("audit_events.target_label", "The target's name at the time, copied rather "
                    + "than joined. An entry reading `account #47` answers nothing once #47 is "
                    + "gone, and the deleted ones are exactly the ones being asked about."),
            Map.entry("audit_events.detail", "What is worth knowing beyond the action itself — "
                    + "the role before a change, the number of rows removed. Never a credential."),
            Map.entry("audit_events.client_ip", "Where the request came from, as far as this "
                    + "deployment can tell. Trustworthy only when APP_TRUST_PROXY_HEADERS matches "
                    + "how the service is actually reached."),

            // ── alerts ──
            Map.entry("alerts.organization_id", "Who this alert is for."),
            Map.entry("alerts.alert_type", "What raised it — a volume spike, a failure rate, an "
                    + "analysis finding."),
            Map.entry("alerts.severity", "HIGH, MEDIUM or LOW. Drives the colour and the ordering."),
            Map.entry("alerts.message", "The one line shown in the list."),
            Map.entry("alerts.details", "The longer explanation shown when it is opened."),
            Map.entry("alerts.domain", "The domain concerned, where the alert is about one."),
            Map.entry("alerts.is_read", "Whether someone has acknowledged it."),
            Map.entry("alerts.created_at", "When it was raised.")
    );

    /**
     * Meanings that do not vary by table, consulted when there is no specific entry.
     *
     * <p>Every {@code id} in this schema is the same idea, and writing it out eleven
     * times would invite eleven slightly different wordings. A table whose key or
     * tenant link genuinely needs saying differently still overrides it above.
     */
    private static final Map<String, String> DEFAULTS = Map.ofEntries(
            Map.entry("id", "Surrogate key, assigned by the database. Has no meaning outside it "
                    + "— what other tables point at."),
            Map.entry("organization_id", "The tenant that owns this row. Every query for this "
                    + "table is filtered by it."),
            Map.entry("user_id", "The account this row belongs to."),
            Map.entry("created_at", "When this row was written, in UTC.")
    );

    /** Words that look wrong in sentence case, because they are acronyms. */
    private static final Map<String, String> ACRONYMS = Map.ofEntries(
            Map.entry("id", "ID"), Map.entry("ip", "IP"), Map.entry("dkim", "DKIM"),
            Map.entry("spf", "SPF"), Map.entry("dmarc", "DMARC"), Map.entry("ssl", "SSL"),
            Map.entry("totp", "TOTP"), Map.entry("json", "JSON"), Map.entry("url", "URL"),
            Map.entry("imap", "IMAP"), Map.entry("ok", "OK"), Map.entry("sp", "Subdomain"),
            Map.entry("pct", "Percentage"), Map.entry("adkim", "DKIM alignment"),
            Map.entry("aspf", "SPF alignment"), Map.entry("org", "Reporter")
    );

    /** Columns whose derived label would be wrong rather than merely plain. */
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("users.is_active", "Status"),
            Map.entry("users.hashed_password", "Password hash"),
            Map.entry("users.totp_secret", "Two-step seed"),
            Map.entry("users.totp_enabled_at", "Two-step since"),
            Map.entry("mailbox_settings.password_cipher", "Password (encrypted)"),
            Map.entry("mailbox_settings.last_run_ok", "Last run"),
            Map.entry("dmarc_reports.report_id", "Provider's report ID"),
            Map.entry("dmarc_reports.org_name", "Reported by"),
            Map.entry("dmarc_reports.org_email", "Reporter contact"),
            Map.entry("dmarc_records.count", "Messages"),
            Map.entry("dmarc_records.source_ip", "Source address"),
            Map.entry("domain_analyses.results_json", "Findings"),
            Map.entry("domain_analyses.score_breakdown_json", "Score breakdown"),
            Map.entry("domain_analyses.recommendations_json", "Recommendations"),
            Map.entry("alerts.is_read", "Acknowledged"),
            Map.entry("alerts.alert_type", "Type")
    );

    /**
     * The columns that answer what the table is for, in the order they answer it.
     *
     * <p>Placed immediately after the key, ahead of everything the engine happens to
     * have stored first. Without this, {@code dmarc_records} led with the report link
     * and the DKIM fields while {@code source_ip} — the address doing the sending,
     * the reason anyone opens that table — sat eighth and off the edge of the grid.
     */
    private static final Map<String, List<String>> LEADING = Map.ofEntries(
            Map.entry("users", List.of("username", "email", "role")),
            Map.entry("organizations", List.of("name")),
            Map.entry("organization_domains", List.of("domain", "verified_at")),
            Map.entry("invitations", List.of("email", "role", "accepted_at")),
            Map.entry("email_verification_tokens", List.of("used_at", "expires_at")),
            Map.entry("password_reset_tokens", List.of("used_at", "expires_at")),
            // A recovery code has nothing to show but whether it has been spent.
            Map.entry("recovery_codes", List.of("used_at")),
            Map.entry("mailbox_settings", List.of("username", "host", "last_run_ok")),
            Map.entry("dmarc_reports", List.of("domain", "org_name", "policy")),
            Map.entry("dmarc_records",
                    List.of("source_ip", "count", "header_from", "disposition")),
            Map.entry("domain_analyses", List.of("domain", "score", "grade")),
            Map.entry("alerts", List.of("severity", "message", "domain")),
            Map.entry("audit_events", List.of("at", "actor", "action", "target_label"))
    );

    /** Columns holding JSON, which the interface should offer to expand rather than truncate. */
    private static final Set<String> JSON_COLUMNS = Set.of(
            "domain_analyses.results_json",
            "domain_analyses.score_breakdown_json",
            "domain_analyses.recommendations_json");

    /**
     * The {@code table.column} keys described here, for the test that holds them to
     * the live schema.
     *
     * <p>A description attached to a column that has since been renamed is worse
     * than no description: it disappears from the interface silently while still
     * reading as authoritative wherever it does appear. The schema is the authority,
     * so the schema is what this is checked against.
     */
    static Set<String> documentedColumns() {
        return COLUMNS.keySet();
    }

    static Set<String> documentedTables() {
        return TABLES.keySet();
    }

    /** {@code table.column} for every column named as leading, for the same check. */
    static Set<String> documentedLeading() {
        Set<String> keys = new java.util.LinkedHashSet<>();
        LEADING.forEach((table, columns) -> columns.forEach(c -> keys.add(table + "." + c)));
        return keys;
    }

    /**
     * Where a column sits among the ones that lead its table, or
     * {@link Integer#MAX_VALUE} when it is not one of them.
     */
    public static int leadingRank(String table, String column) {
        List<String> leading = LEADING.get(key(table));
        if (leading == null) {
            return Integer.MAX_VALUE;
        }
        int position = leading.indexOf(key(column));
        return position < 0 ? Integer.MAX_VALUE : position;
    }

    public static boolean isLeading(String table, String column) {
        return leadingRank(table, column) != Integer.MAX_VALUE;
    }

    public static TableDoc table(String name) {
        return TABLES.getOrDefault(key(name), UNKNOWN);
    }

    public static String tableLabel(String name) {
        TableDoc doc = table(name);
        return doc.label() != null ? doc.label() : humanise(name);
    }

    public static String columnDescription(String table, String column) {
        String specific = COLUMNS.get(key(table) + "." + key(column));
        return specific != null ? specific : DEFAULTS.get(key(column));
    }

    public static boolean isJson(String table, String column) {
        return JSON_COLUMNS.contains(key(table) + "." + key(column));
    }

    /**
     * A readable name for a column.
     *
     * @param references the table it points into, when it is a foreign key —
     *                   {@code organization_id} reads better as "Organization" once
     *                   the value beside it is a name rather than a number
     */
    public static String columnLabel(String table, String column, String references) {
        String explicit = LABELS.get(key(table) + "." + key(column));
        if (explicit != null) {
            return explicit;
        }
        String name = key(column);
        if (references != null && name.endsWith("_id")) {
            return humanise(name.substring(0, name.length() - 3));
        }
        return humanise(name);
    }

    /** {@code last_run_summary} to "Last run summary", {@code dkim_result} to "DKIM result". */
    static String humanise(String raw) {
        String[] parts = key(raw).split("_");
        StringBuilder out = new StringBuilder();

        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            String word = ACRONYMS.get(part);
            if (word == null) {
                word = out.isEmpty()
                        ? Character.toUpperCase(part.charAt(0)) + part.substring(1)
                        : part;
            } else if (!out.isEmpty()) {
                // An acronym keeps its case wherever it falls; a spelled-out
                // replacement should not start a mid-sentence capital.
                word = word.equals(word.toUpperCase(Locale.ROOT))
                        ? word
                        : word.toLowerCase(Locale.ROOT);
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(word);
        }
        return out.isEmpty() ? raw : out.toString();
    }

    private static String key(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
