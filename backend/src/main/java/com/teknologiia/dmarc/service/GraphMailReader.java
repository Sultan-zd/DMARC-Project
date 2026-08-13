package com.teknologiia.dmarc.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.teknologiia.dmarc.dto.ingest.IngestionResult;
import com.teknologiia.dmarc.model.MailboxSettings;
import com.teknologiia.dmarc.model.Organization;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Reads a Microsoft 365 mailbox through the Graph API.
 *
 * <p>This exists because Microsoft removed Basic authentication from Exchange
 * Online: no password will open an IMAP session against a Microsoft 365 mailbox,
 * so the IMAP path in {@link EmailService} cannot reach one at all. Graph
 * authenticates as a registered application instead, over ordinary HTTPS.
 *
 * <p>Two consequences are worth stating. There is no port 993 involved, so a
 * firewall that blocks legacy mail ports — which is most of them — does not stand
 * in the way. And the application acts as itself rather than as a person, so no
 * human credential is stored and none expires when somebody changes their
 * password.
 *
 * <p>What is <em>not</em> different: once the bytes of an attachment are in hand
 * they go through exactly the same {@link ReportIngestionService} as an IMAP
 * attachment or an uploaded file. The format sniffing, the bounded decompression
 * and the per-tenant deduplication are shared, so a report cannot be read one way
 * here and another way there.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GraphMailReader {

    private static final String LOGIN_HOST = "https://login.microsoftonline.com";
    private static final String GRAPH = "https://graph.microsoft.com/v1.0";

    /** Same ceiling as the IMAP path, for the same reason: a run must end. */
    private static final int MAX_MESSAGES_PER_RUN = 200;
    private static final int PAGE_SIZE = 50;
    private static final int FIRST_RUN_LOOKBACK_DAYS = 30;

    /** Graph returns attachment bytes inline; a single response should stay sane. */
    private static final int MAX_ATTACHMENT_RESPONSE_BYTES = 40 * 1024 * 1024;

    private final ReportIngestionService ingestionService;
    private final ObjectMapper json = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * Collects every report that arrived since the window opens.
     *
     * @param secret the registration's client secret, already decrypted
     */
    public IngestionResult collect(Organization organization, MailboxSettings mailbox, String secret) {
        try {
            String token = accessToken(mailbox, secret);
            List<String> messageIds = recentMessageIds(mailbox, token);
            log.info("Graph run for {}: {} message(s) with attachments in the window",
                    mailbox.getUsername(), messageIds.size());

            IngestionResult result = IngestionResult.empty();
            for (String id : messageIds) {
                result = result.merge(readAttachments(organization, mailbox, token, id));
            }
            return result;

        } catch (GraphException e) {
            log.error("Graph collection failed for {}", mailbox.getUsername(), e);
            return new IngestionResult(0, 0, 0, 0, 0, List.of(e.getMessage()));
        } catch (Exception e) {
            log.error("Graph collection failed for {}", mailbox.getUsername(), e);
            return new IngestionResult(0, 0, 0, 0, 0,
                    List.of("Could not read the mailbox over Microsoft Graph: " + e.getMessage()));
        }
    }

    // ── Authentication ────────────────────────────────────────────────────

    /**
     * Client-credentials token: the application acting as itself, with no user.
     *
     * <p>The failure messages here are deliberately explicit. Entra ID answers with
     * codes like {@code AADSTS7000215} that mean nothing to the administrator
     * reading the mailbox card, and the three mistakes below account for almost
     * every failed setup.
     */
    private String accessToken(MailboxSettings mailbox, String secret) throws Exception {
        String body = "client_id=" + enc(mailbox.getClientId())
                + "&client_secret=" + enc(secret)
                + "&scope=" + enc("https://graph.microsoft.com/.default")
                + "&grant_type=client_credentials";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(LOGIN_HOST + "/" + mailbox.getTenantId() + "/oauth2/v2.0/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new GraphException("Microsoft refused the application's credentials. "
                    + explainSignIn(response.body()));
        }

        JsonNode node = json.readTree(response.body());
        String token = node.path("access_token").asText(null);
        if (token == null || token.isBlank()) {
            throw new GraphException("Microsoft returned no access token for this registration.");
        }
        return token;
    }

    /** Turns the AADSTS code into something the person configuring the mailbox can act on. */
    private String explainSignIn(String body) {
        String text = body == null ? "" : body;
        if (text.contains("AADSTS7000215") || text.contains("invalid_client")) {
            return "The client secret is wrong or has expired — Entra ID secrets expire, "
                    + "often after six or twelve months. Create a new one and save it here.";
        }
        if (text.contains("AADSTS700016") || text.contains("unauthorized_client")) {
            return "No application with this client id exists in that directory. "
                    + "Check the client id, and that the tenant id is the right directory.";
        }
        if (text.contains("AADSTS90002")) {
            return "That tenant id does not exist. It is the Directory (tenant) ID from the "
                    + "Entra ID overview page, not the domain name.";
        }
        return "Check the directory, client id and secret. Microsoft said: " + brief(text);
    }

    // ── Reading ───────────────────────────────────────────────────────────

    /**
     * The ids of messages carrying attachments, newest first.
     *
     * <p>Only messages with attachments are asked for: a report always arrives as
     * one, and a mailbox that also receives ordinary mail should not cost a request
     * per irrelevant message.
     */
    private List<String> recentMessageIds(MailboxSettings mailbox, String token) throws Exception {
        String since = DateTimeFormatter.ISO_INSTANT.format(
                lookBackFrom(mailbox).toInstant(ZoneOffset.UTC));

        String url = GRAPH + "/users/" + enc(mailbox.getUsername()) + "/messages"
                + "?$select=" + enc("id,subject,receivedDateTime,hasAttachments")
                + "&$filter=" + enc("hasAttachments eq true and receivedDateTime ge " + since)
                + "&$orderby=" + enc("receivedDateTime desc")
                + "&$top=" + PAGE_SIZE;

        List<String> ids = new ArrayList<>();
        while (url != null && ids.size() < MAX_MESSAGES_PER_RUN) {
            JsonNode page = get(url, token, "listing messages");
            for (JsonNode message : page.path("value")) {
                ids.add(message.path("id").asText());
                if (ids.size() >= MAX_MESSAGES_PER_RUN) break;
            }
            // Graph pages with an opaque link rather than an offset; it already
            // carries the filter and ordering, so it is followed as given.
            url = page.hasNonNull("@odata.nextLink") ? page.get("@odata.nextLink").asText() : null;
        }
        return ids;
    }

    private IngestionResult readAttachments(Organization organization, MailboxSettings mailbox,
                                            String token, String messageId) {
        try {
            JsonNode page = get(GRAPH + "/users/" + enc(mailbox.getUsername())
                    + "/messages/" + messageId + "/attachments", token, "reading attachments");

            IngestionResult result = IngestionResult.empty();
            for (JsonNode attachment : page.path("value")) {
                // Only file attachments carry bytes. An item attachment is a
                // forwarded message and a reference attachment is a link to
                // cloud storage; neither holds a report.
                if (!"#microsoft.graph.fileAttachment".equals(attachment.path("@odata.type").asText())) {
                    continue;
                }
                String name = attachment.path("name").asText("(unnamed attachment)");
                String encoded = attachment.path("contentBytes").asText(null);
                if (encoded == null) continue;

                byte[] content = Base64.getDecoder().decode(encoded);
                try (var stream = new ByteArrayInputStream(content)) {
                    result = result.merge(ingestionService.ingest(organization, name, stream));
                }
            }
            return result;

        } catch (Exception e) {
            log.warn("Could not read attachments of message {}", messageId, e);
            return new IngestionResult(0, 0, 0, 0, 0,
                    List.of("A message could not be read: " + e.getMessage()));
        }
    }

    private JsonNode get(String url, String token, String what) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();

        // Streamed and bounded while reading, not measured afterwards. Graph returns
        // attachment bytes inline in the JSON, so an attachments response is as large
        // as the attachments themselves; buffering first and checking the size after
        // would have allocated the memory it was meant to protect.
        HttpResponse<java.io.InputStream> response =
                http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        String body;
        try (var stream = response.body()) {
            body = new String(readBounded(stream), StandardCharsets.UTF_8);
        }

        if (response.statusCode() == 403) {
            throw new GraphException("Microsoft accepted the application but refused the mailbox. "
                    + "The registration needs the Mail.Read application permission with admin "
                    + "consent granted, and — if an application access policy restricts it — that "
                    + "policy must include this mailbox.");
        }
        if (response.statusCode() == 404) {
            throw new GraphException("No mailbox found at " + mailbox(url)
                    + ". Use the full address of the mailbox, and check it has a licence.");
        }
        if (response.statusCode() != 200) {
            throw new GraphException("Microsoft Graph refused the request while " + what
                    + " (HTTP " + response.statusCode() + "): " + brief(body));
        }
        return json.readTree(body);
    }

    // ── Odds and ends ─────────────────────────────────────────────────────

    /**
     * How far back a run reaches — the same rule as the IMAP path, deliberately.
     *
     * <p>From a day before the last successful run, so nothing falls between two
     * windows. Duplicates cost nothing because ingestion recognises a report id it
     * already holds; a gap costs a report nobody will look for again.
     */
    private LocalDateTime lookBackFrom(MailboxSettings mailbox) {
        return mailbox.getLastRunAt() != null && Boolean.TRUE.equals(mailbox.getLastRunOk())
                ? mailbox.getLastRunAt().minusDays(1)
                : LocalDateTime.now(ZoneOffset.UTC).minusDays(FIRST_RUN_LOOKBACK_DAYS);
    }

    /**
     * Reads a response, refusing rather than growing without limit.
     *
     * <p>The ceiling is on what arrives from Microsoft. Whatever survives it still
     * meets the ingestion service's own bounds afterwards — this only stops a single
     * oversized response from exhausting the heap before ingestion gets a say.
     */
    private static byte[] readBounded(java.io.InputStream stream) throws java.io.IOException {
        var buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[16 * 1024];
        int read;
        while ((read = stream.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
            if (buffer.size() > MAX_ATTACHMENT_RESPONSE_BYTES) {
                throw new GraphException("A message carries more attachment data than this "
                        + "application will load at once. Its attachments were not read.");
            }
        }
        return buffer.toByteArray();
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    /** The address back out of a request URL, for an error message that names it. */
    private static String mailbox(String url) {
        int start = url.indexOf("/users/");
        if (start < 0) return "that address";
        int end = url.indexOf('/', start + 7);
        return java.net.URLDecoder.decode(
                end < 0 ? url.substring(start + 7) : url.substring(start + 7, end),
                StandardCharsets.UTF_8);
    }

    /** Microsoft's error bodies run long; the card has one line. */
    private static String brief(String body) {
        String flat = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        return flat.length() > 220 ? flat.substring(0, 220) + "…" : flat;
    }

    /** Carries a message already written for the person reading the mailbox card. */
    static class GraphException extends RuntimeException {
        GraphException(String message) { super(message); }
    }
}
