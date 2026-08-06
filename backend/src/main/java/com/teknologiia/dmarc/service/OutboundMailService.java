package com.teknologiia.dmarc.service;

import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * Sends the three emails this application produces: confirm your address, reset
 * your password, and you have been invited.
 *
 * <p>Both used to be written to the log with a comment saying SMTP was not
 * configured yet, which meant an administrator had to read the server log and pass
 * the link on by hand. Nothing about the flow changes here — the token, its expiry
 * and its single use were already real — only the delivery.
 *
 * <p>When no host is configured the link is still logged, so a developer with no
 * mail server can complete a sign-up. That fallback is deliberate and it is
 * announced at startup, because a deployment silently not sending its confirmation
 * emails looks exactly like one where sign-up is broken.
 */
@Service
@Slf4j
public class OutboundMailService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.host:}")
    private String mailHost;

    @Value("${app.mail.from:}")
    private String from;

    @Value("${app.mail.from-name:Teknologiia DMARC}")
    private String fromName;

    @Value("${app.public-url}")
    private String publicUrl;

    public OutboundMailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    /** True when a mail host is configured and messages will actually leave. */
    public boolean isConfigured() {
        return mailHost != null && !mailHost.isBlank();
    }

    /** Asynchronous: a slow SMTP server must not hold up the sign-up response. */
    @Async
    public void sendVerification(String to, String username, String token) {
        String link = publicUrl + "/verify?token=" + token;
        send(to,
                "Confirm your email address",
                body(
                        "Confirm your email address",
                        "Hello " + escape(username) + ",",
                        "Your account is created but cannot sign in until this address is "
                                + "confirmed. That is the point of this message: it proves the "
                                + "mailbox belongs to you.",
                        "Confirm my address",
                        link,
                        "The link works once and expires in 24 hours. If you did not create "
                                + "this account, ignore this message — the account stays disabled."),
                "Verification link for " + to, link);
    }

    /**
     * The way back in for someone who has lost their password.
     *
     * <p>Says plainly that ignoring the message changes nothing. A reset mail that
     * arrives unasked-for is either a mistyped address or somebody probing, and in
     * both cases the right instruction is to do nothing — a recipient who is told
     * that does not go looking for a way to "cancel" it.
     */
    @Async
    public void sendPasswordReset(String to, String username, String token, long ttlMinutes) {
        String link = publicUrl + "/reset-password?token=" + token;
        String validity = ttlMinutes % 60 == 0
                ? (ttlMinutes / 60) + (ttlMinutes == 60 ? " hour" : " hours")
                : ttlMinutes + " minutes";

        send(to,
                "Reset your password",
                body(
                        "Reset your password",
                        "Hello " + escape(username) + ",",
                        "Someone asked to reset the password for this account. Choose a new "
                                + "one with the button below — you will not be asked for the old "
                                + "password, because following this link from your mailbox is "
                                + "what proves the account is yours.",
                        "Choose a new password",
                        link,
                        "The link works once and expires in " + validity + ". If you did not ask "
                                + "for this, ignore the message: nothing changes and your current "
                                + "password keeps working."),
                "Password reset link for " + to, link);
    }

    @Async
    public void sendInvitation(String to, String organization, String invitedBy,
                               String role, String link) {
        send(to,
                "You have been invited to " + organization,
                body(
                        "Join " + escape(organization),
                        "Hello,",
                        escape(invitedBy) + " has invited you to join <strong>"
                                + escape(organization) + "</strong> as " + escape(role)
                                + ". Accepting puts your account inside their team, with access "
                                + "to the domains and reports they already hold.",
                        "Accept the invitation",
                        link,
                        "The link works once and expires in 7 days."),
                "Invitation for " + to, link);
    }

    /**
     * Delivers the message, or logs the link when no mail host is configured.
     *
     * <p>A failure to send is logged and swallowed: the account and its token already
     * exist, and the address can be confirmed later from a fresh link. Private, and
     * the {@code @Async} sits on the public callers — a self-invocation never passes
     * through the proxy that makes it asynchronous.
     */
    private void send(String to, String subject, String html,
                      String fallbackLabel, String fallbackLink) {
        if (!isConfigured()) {
            log.info("{} — {}", fallbackLabel, fallbackLink);
            return;
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper =
                    new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
            if (!from.isBlank()) {
                helper.setFrom(from, fromName);
            }
            mailSender.send(message);
            log.info("Sent '{}' to {}", subject, to);
        } catch (Exception e) {
            // The link is logged on failure so the sign-up is still completable.
            log.error("Could not send '{}' to {}: {} — {}: {}",
                    subject, to, e.getMessage(), fallbackLabel, fallbackLink);
        }
    }

    /**
     * One layout for both messages.
     *
     * <p>Table-based and inline-styled on purpose: mail clients strip stylesheets and
     * many still lay out with tables. The brand green is the deep variant, which is
     * the one that keeps its contrast when a client forces a white background.
     */
    private String body(String heading, String greeting, String paragraph,
                        String buttonLabel, String link, String footnote) {
        return """
                <table width="100%%" cellpadding="0" cellspacing="0" style="background:#F4F6F5;padding:32px 0;font-family:Segoe UI,Helvetica,Arial,sans-serif;">
                  <tr><td align="center">
                    <table width="560" cellpadding="0" cellspacing="0" style="background:#ffffff;border-radius:10px;overflow:hidden;border:1px solid #DDE4E0;">
                      <tr><td style="background:#27292F;padding:18px 28px;border-bottom:3px solid #00AE4E;">
                        <span style="color:#ffffff;font-size:15px;font-weight:600;letter-spacing:.08em;">TEKNOLOGIIA</span>
                        <span style="color:#9AA3A8;font-size:13px;"> &middot; DMARC Dashboard</span>
                      </td></tr>
                      <tr><td style="padding:32px 28px;">
                        <h1 style="margin:0 0 18px;font-size:21px;color:#27292F;">%s</h1>
                        <p style="margin:0 0 14px;font-size:15px;line-height:1.6;color:#3F4650;">%s</p>
                        <p style="margin:0 0 26px;font-size:15px;line-height:1.6;color:#3F4650;">%s</p>
                        <table cellpadding="0" cellspacing="0"><tr><td style="background:#00713A;border-radius:6px;">
                          <a href="%s" style="display:inline-block;padding:13px 26px;color:#ffffff;font-size:15px;font-weight:600;text-decoration:none;">%s</a>
                        </td></tr></table>
                        <p style="margin:24px 0 0;font-size:13px;line-height:1.6;color:#6B7480;">%s</p>
                        <p style="margin:14px 0 0;font-size:12px;line-height:1.6;color:#8A9199;word-break:break-all;">
                          If the button does not work, paste this into your browser:<br>%s
                        </p>
                      </td></tr>
                    </table>
                  </td></tr>
                </table>
                """.formatted(escape(heading), escape(greeting), paragraph,
                        link, escape(buttonLabel), escape(footnote), link);
    }

    /** Values reaching the template come from user input; none of them are markup. */
    private static String escape(String value) {
        return value == null ? "" : value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
