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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Report mailboxes belong to an organization, not to the server.
 *
 * <p>They used to be one set of credentials in {@code application.properties}, so
 * every tenant's collection run opened the same inbox and whichever one pressed the
 * button took ownership of what it found. A DMARC aggregate report lists every IP
 * address sending as the domains it covers, so that was one customer reading
 * another's mail traffic.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        // 32 bytes, base64: without a key nothing can be stored at all.
        "app.secrets.key=c2l4dGVlbi1ieXRlcy10aW1lcy10d28tZXhhY3RseS4=",
})
class MailboxIsolationTest {

    @Autowired private MailboxSettingsService service;
    @Autowired private MailboxSettingsRepository repository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private SecretCipher cipher;

    private Organization acme;
    private Organization rival;

    @BeforeEach
    void seed() {
        String suffix = java.util.UUID.randomUUID().toString().substring(0, 8);
        acme = organizationRepository.save(Organization.builder().name("Acme " + suffix).build());
        rival = organizationRepository.save(Organization.builder().name("Rival " + suffix).build());
    }

    private MailboxSettingsRequest request(String host, String user, String password) {
        return new MailboxSettingsRequest(MailboxKind.IMAP, host, 993, user, password,
                null, null, true, true);
    }

    @Test
    @DisplayName("each organization sees only its own mailbox")
    void mailboxesAreScoped() {
        service.save(acme.getId(), request("imap.acme.test", "reports@acme.test", "acme-secret"));
        service.save(rival.getId(), request("imap.rival.test", "reports@rival.test", "rival-secret"));

        assertThat(service.get(acme.getId()).username()).isEqualTo("reports@acme.test");
        assertThat(service.get(rival.getId()).username()).isEqualTo("reports@rival.test");
    }

    @Test
    @DisplayName("an organization with no mailbox sees nothing, not somebody else's")
    void unconfiguredOrganizationSeesNothing() {
        service.save(acme.getId(), request("imap.acme.test", "reports@acme.test", "acme-secret"));

        var view = service.get(rival.getId());

        assertThat(view.configured()).isFalse();
        assertThat(view.host()).isNull();
        assertThat(view.username()).isNull();
    }

    @Test
    @DisplayName("the password is never returned, only replaced")
    void passwordIsWriteOnly() {
        service.save(acme.getId(), request("imap.acme.test", "reports@acme.test", "acme-secret"));

        // The response record has no password component at all, so there is no path
        // by which the API could return one. What is stored is not the plaintext.
        var stored = repository.findByOrganizationId(acme.getId()).orElseThrow();
        assertThat(stored.getPasswordCipher()).isNotEqualTo("acme-secret");
        assertThat(cipher.decrypt(stored.getPasswordCipher())).isEqualTo("acme-secret");
    }

    @Test
    @DisplayName("saving again without a password keeps the stored one")
    void omittingThePasswordKeepsIt() {
        service.save(acme.getId(), request("imap.acme.test", "reports@acme.test", "acme-secret"));
        service.save(acme.getId(), request("imap2.acme.test", "reports@acme.test", null));

        var stored = repository.findByOrganizationId(acme.getId()).orElseThrow();
        assertThat(stored.getHost()).isEqualTo("imap2.acme.test");
        assertThat(cipher.decrypt(stored.getPasswordCipher())).isEqualTo("acme-secret");
    }

    @Test
    @DisplayName("configuring for the first time requires a password")
    void firstConfigurationNeedsAPassword() {
        assertThatThrownBy(() -> service.save(acme.getId(),
                request("imap.acme.test", "reports@acme.test", null)))
                .hasMessageContaining("password is needed");
    }

    @Test
    @DisplayName("removing one organization's mailbox leaves the other's alone")
    void removalIsScoped() {
        service.save(acme.getId(), request("imap.acme.test", "reports@acme.test", "acme-secret"));
        service.save(rival.getId(), request("imap.rival.test", "reports@rival.test", "rival-secret"));

        service.remove(acme.getId());

        assertThat(service.get(acme.getId()).configured()).isFalse();
        assertThat(service.get(rival.getId()).configured()).isTrue();
    }

    @Test
    @DisplayName("only mailboxes opted into polling are collected on a schedule")
    void pollingIsOptional() {
        service.save(acme.getId(), request("imap.acme.test", "reports@acme.test", "acme-secret"));
        service.save(rival.getId(),
                new MailboxSettingsRequest(MailboxKind.IMAP, "imap.rival.test", 993,
                        "reports@rival.test", "rival-secret", null, null, true, false));

        assertThat(repository.findByPollingEnabledTrue())
                .extracting(m -> m.getOrganization().getId())
                .contains(acme.getId())
                .doesNotContain(rival.getId());
    }

    @Test
    @DisplayName("encryption survives a round trip and does not repeat itself")
    void encryptionIsSound() {
        String first = cipher.encrypt("the same value");
        String second = cipher.encrypt("the same value");

        // A fresh nonce each time: identical passwords must not produce identical
        // ciphertext, or the database reveals which accounts share one.
        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo("the same value");
        assertThat(cipher.decrypt(second)).isEqualTo("the same value");
    }
}
