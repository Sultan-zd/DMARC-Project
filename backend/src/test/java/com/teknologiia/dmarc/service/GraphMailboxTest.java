package com.teknologiia.dmarc.service;

import com.teknologiia.dmarc.dto.mailbox.MailboxSettingsRequest;
import com.teknologiia.dmarc.model.MailboxKind;
import com.teknologiia.dmarc.model.Organization;
import com.teknologiia.dmarc.repository.MailboxSettingsRepository;
import com.teknologiia.dmarc.repository.OrganizationRepository;
import com.teknologiia.dmarc.security.SecretCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Microsoft 365 mailboxes, which cannot be reached over IMAP at all.
 *
 * <p>Exchange Online no longer accepts Basic authentication for IMAP, POP or EWS.
 * No password — app password included — will open an IMAP session against a
 * Microsoft 365 mailbox, so a deployment whose reports arrive at one has to read
 * it through Graph as a registered application.
 *
 * <p>These tests hold the configuration rules rather than the network calls: the
 * failures worth preventing are a mailbox saved with settings that cannot work,
 * and a provider switch that silently keeps a credential belonging to the other
 * one.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.secrets.key=c2l4dGVlbi1ieXRlcy10aW1lcy10d28tZXhhY3RseS4=",
})
class GraphMailboxTest {

    @Autowired private MailboxSettingsService service;
    @Autowired private MailboxSettingsRepository repository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private SecretCipher cipher;

    private Organization org;

    @BeforeEach
    void seed() {
        org = organizationRepository.save(Organization.builder()
                .name("Graph " + UUID.randomUUID().toString().substring(0, 8)).build());
    }

    private static MailboxSettingsRequest graph(String mailbox, String tenant,
                                                String client, String secret) {
        return new MailboxSettingsRequest(MailboxKind.MICROSOFT_GRAPH, null, 0, mailbox,
                secret, tenant, client, true, true);
    }

    private static MailboxSettingsRequest imap(String host, String user, String password) {
        return new MailboxSettingsRequest(MailboxKind.IMAP, host, 993, user, password,
                null, null, true, true);
    }

    @Test
    @DisplayName("a Microsoft mailbox is stored with its directory and application")
    void graphMailboxIsStored() {
        var saved = service.save(org.getId(), graph("dmarcreports@acme.test",
                "11111111-2222-3333-4444-555555555555",
                "66666666-7777-8888-9999-000000000000", "the-client-secret"));

        assertThat(saved.kind()).isEqualTo(MailboxKind.MICROSOFT_GRAPH);
        assertThat(saved.tenantId()).isEqualTo("11111111-2222-3333-4444-555555555555");
        assertThat(saved.clientId()).isEqualTo("66666666-7777-8888-9999-000000000000");
        // Not configurable for Graph, and written rather than left empty so the row
        // says what it actually talks to.
        assertThat(saved.host()).isEqualTo("graph.microsoft.com");
        assertThat(saved.port()).isEqualTo(443);
    }

    @Test
    @DisplayName("the client secret is encrypted, never returned")
    void secretIsEncryptedAndNeverReturned() {
        service.save(org.getId(), graph("dmarcreports@acme.test", "tenant-1", "client-1", "s3cr3t"));

        var stored = repository.findByOrganizationId(org.getId()).orElseThrow();
        assertThat(stored.getPasswordCipher()).isNotNull().doesNotContain("s3cr3t");
        assertThat(cipher.decrypt(stored.getPasswordCipher())).isEqualTo("s3cr3t");

        // The response record has no secret field at all; this is the guard that
        // nobody adds one later without noticing.
        assertThat(service.get(org.getId()).toString()).doesNotContain("s3cr3t");
    }

    @Test
    @DisplayName("a Microsoft mailbox without a directory or application is refused")
    void graphNeedsTenantAndClient() {
        assertThatThrownBy(() -> service.save(org.getId(),
                graph("dmarcreports@acme.test", null, "client-1", "secret")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("directory");

        assertThatThrownBy(() -> service.save(org.getId(),
                graph("dmarcreports@acme.test", "tenant-1", "  ", "secret")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("application");
    }

    @Test
    @DisplayName("Graph needs the full mailbox address, not an IMAP-style user name")
    void graphNeedsAnAddress() {
        assertThatThrownBy(() -> service.save(org.getId(),
                graph("dmarcreports", "tenant-1", "client-1", "secret")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("full address");
    }

    @Test
    @DisplayName("switching provider will not reuse the other one's credential")
    void switchingProviderDemandsANewSecret() {
        service.save(org.getId(), imap("imap.gmail.com", "reports@acme.test", "an-app-password"));

        // An app password is not a client secret. Carrying it over would fail at the
        // next collection run with a message about the wrong thing entirely.
        assertThatThrownBy(() -> service.save(org.getId(),
                graph("dmarcreports@acme.test", "tenant-1", "client-1", null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("client secret");
    }

    @Test
    @DisplayName("moving back to IMAP clears the Microsoft settings")
    void movingBackToImapClearsGraphFields() {
        service.save(org.getId(), graph("dmarcreports@acme.test", "tenant-1", "client-1", "secret"));
        var saved = service.save(org.getId(),
                imap("imap.gmail.com", "reports@acme.test", "an-app-password"));

        assertThat(saved.kind()).isEqualTo(MailboxKind.IMAP);
        assertThat(saved.tenantId()).isNull();
        assertThat(saved.clientId()).isNull();
        assertThat(saved.host()).isEqualTo("imap.gmail.com");
    }

    @Test
    @DisplayName("an existing Microsoft mailbox keeps its secret when only a field changes")
    void secretSurvivesAnEdit() {
        service.save(org.getId(), graph("dmarcreports@acme.test", "tenant-1", "client-1", "secret"));
        service.save(org.getId(), graph("dmarc@acme.test", "tenant-1", "client-1", null));

        var stored = repository.findByOrganizationId(org.getId()).orElseThrow();
        assertThat(stored.getUsername()).isEqualTo("dmarc@acme.test");
        assertThat(cipher.decrypt(stored.getPasswordCipher())).isEqualTo("secret");
    }

    @Test
    @DisplayName("a row written before Graph existed still reads as IMAP")
    void nullKindReadsAsImap() {
        // Rows predating the column carry no kind. Treating null as "unknown" would
        // take every existing deployment's mailbox out of service on upgrade.
        assertThat(MailboxKind.orDefault(null)).isEqualTo(MailboxKind.IMAP);
        assertThat(MailboxKind.orDefault(MailboxKind.MICROSOFT_GRAPH))
                .isEqualTo(MailboxKind.MICROSOFT_GRAPH);

        service.save(org.getId(), imap("imap.gmail.com", "reports@acme.test", "pw"));
        var stored = repository.findByOrganizationId(org.getId()).orElseThrow();
        stored.setKind(null);
        repository.save(stored);

        assertThat(service.get(org.getId()).kind()).isEqualTo(MailboxKind.IMAP);
    }

    @Test
    @DisplayName("an IMAP mailbox still needs a host")
    void imapStillNeedsAHost() {
        assertThatThrownBy(() -> service.save(org.getId(),
                new MailboxSettingsRequest(MailboxKind.IMAP, "  ", 993, "reports@acme.test",
                        "pw", null, null, true, true)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("host");
    }
}
