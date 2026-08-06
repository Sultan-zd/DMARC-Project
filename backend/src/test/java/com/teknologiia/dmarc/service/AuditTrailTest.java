package com.teknologiia.dmarc.service;

import com.teknologiia.dmarc.dto.platform.AuditPage;
import com.teknologiia.dmarc.dto.user.UserCreateRequest;
import com.teknologiia.dmarc.model.Organization;
import com.teknologiia.dmarc.model.User;
import com.teknologiia.dmarc.repository.AuditEventRepository;
import com.teknologiia.dmarc.repository.OrganizationRepository;
import com.teknologiia.dmarc.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The trail that answers "who did this, and when".
 *
 * <p>Every one of these goes through the service that performs the action rather
 * than calling {@link AuditService} directly. Testing the writer in isolation would
 * prove that a row can be written, not that anything writes one — and the failure
 * this guards against is an action quietly going unrecorded.
 */
@SpringBootTest
@ActiveProfiles("test")
class AuditTrailTest {

    @Autowired private AuditService auditService;
    @Autowired private UserService userService;
    @Autowired private SessionService sessionService;
    @Autowired private AuditEventRepository auditRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private Organization organization;
    private User member;
    private String suffix;

    @BeforeEach
    void seed() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        organization = organizationRepository.save(
                Organization.builder().name("Audit " + suffix).build());

        member = userRepository.save(User.builder()
                .organization(organization)
                .username("member-" + suffix)
                .email("member-" + suffix + "@audit.test")
                .hashedPassword(passwordEncoder.encode("Harbour-Lantern-Grey-41"))
                .role("ANALYST")
                .active(true)
                .build());

        // A second administrator, so removing or demoting the first is allowed.
        userRepository.save(User.builder()
                .organization(organization)
                .username("keeper-" + suffix)
                .email("keeper-" + suffix + "@audit.test")
                .hashedPassword(passwordEncoder.encode("Harbour-Lantern-Grey-41"))
                .role("ADMIN")
                .active(true)
                .build());
    }

    /** The entries recorded against a target, newest first. */
    private List<AuditPage.Entry> entriesFor(String label) {
        return auditService.search(null, null, null, 1, 200).entries().stream()
                .filter(e -> label.equals(e.targetLabel()))
                .toList();
    }

    // ─── Actions leave a trace ──────────────────────────────────────

    @Test
    @DisplayName("deleting an account is recorded, with the name it had")
    void deletionIsRecorded() {
        Long id = member.getId();
        String name = member.getUsername();

        userService.deleteUser(organization.getId(), id, 0L);

        assertThat(entriesFor(name))
                .as("the question this table exists for")
                .anySatisfy(e -> {
                    assertThat(e.action()).isEqualTo(AuditAction.ACCOUNT_DELETED);
                    assertThat(e.targetId()).isEqualTo(id);
                    // The row it points at is gone; the label is why the entry
                    // still means something.
                    assertThat(e.targetLabel()).isEqualTo(name);
                });

        assertThat(userRepository.findById(id)).isEmpty();
    }

    @Test
    @DisplayName("a role change records what it was before")
    void roleChangeRecordsThePrevious() {
        userService.changeRole(organization.getId(), member.getId(), "VIEWER", 0L);

        assertThat(entriesFor(member.getUsername()))
                .filteredOn(e -> AuditAction.ACCOUNT_ROLE_CHANGED.equals(e.action()))
                .singleElement()
                .satisfies(e -> assertThat(e.detail()).isEqualTo("ANALYST to VIEWER"));
    }

    @Test
    @DisplayName("disabling records both the disable and the sessions it ended")
    void disablingRecordsBoth() {
        userService.setActive(organization.getId(), member.getId(), false, 0L);

        assertThat(entriesFor(member.getUsername()))
                .extracting(AuditPage.Entry::action)
                .contains(AuditAction.ACCOUNT_DISABLED, AuditAction.SESSIONS_REVOKED);
    }

    @Test
    @DisplayName("creating an account is recorded")
    void creationIsRecorded() {
        String name = "created-" + suffix;
        userService.createUser(organization.getId(), new UserCreateRequest(
                name, name + "@audit.test", null, "VIEWER"));

        assertThat(entriesFor(name))
                .extracting(AuditPage.Entry::action)
                .contains(AuditAction.ACCOUNT_CREATED);
    }

    @Test
    @DisplayName("revoking sessions names who asked for it")
    void revocationNamesTheActor() {
        sessionService.revokeAll(member, "sultan", "laptop reported missing");

        assertThat(entriesFor(member.getUsername()))
                .filteredOn(e -> AuditAction.SESSIONS_REVOKED.equals(e.action()))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.actor()).isEqualTo("sultan");
                    assertThat(e.detail()).isEqualTo("laptop reported missing");
                });
    }

    // ─── Reading it back ────────────────────────────────────────────

    @Test
    @DisplayName("the trail can be narrowed to one actor")
    void filtersByActor() {
        sessionService.revokeAll(member, "alice-" + suffix, "one");
        sessionService.revokeAll(member, "bob-" + suffix, "two");

        AuditPage page = auditService.search("alice-" + suffix, null, null, 1, 50);

        assertThat(page.entries()).isNotEmpty();
        assertThat(page.entries()).allSatisfy(
                e -> assertThat(e.actor()).isEqualTo("alice-" + suffix));
    }

    @Test
    @DisplayName("and to one kind of action")
    void filtersByAction() {
        userService.setActive(organization.getId(), member.getId(), false, 0L);

        AuditPage page = auditService.search(null, AuditAction.ACCOUNT_DISABLED, null, 1, 50);

        assertThat(page.entries()).isNotEmpty();
        assertThat(page.entries()).allSatisfy(
                e -> assertThat(e.action()).isEqualTo(AuditAction.ACCOUNT_DISABLED));
    }

    @Test
    @DisplayName("newest first, because that is what is being looked for")
    void newestFirst() {
        sessionService.revokeAll(member, "first-" + suffix, "one");
        sessionService.revokeAll(member, "second-" + suffix, "two");

        List<AuditPage.Entry> entries = entriesFor(member.getUsername());

        assertThat(entries).hasSizeGreaterThanOrEqualTo(2);
        assertThat(entries.get(0).actor()).isEqualTo("second-" + suffix);
    }

    @Test
    @DisplayName("the filter offers only actions that actually happened")
    void offersPresentActionsOnly() {
        userService.setActive(organization.getId(), member.getId(), false, 0L);

        assertThat(auditService.search(null, null, null, 1, 1).actions())
                .contains(AuditAction.ACCOUNT_DISABLED)
                .allSatisfy(a -> assertThat(a).isNotBlank());
    }

    // ─── What it must never do ──────────────────────────────────────

    @Test
    @DisplayName("a failure to record never fails the action it describes")
    void recordingNeverThrows() {
        // 500 characters is the column; a longer detail must be trimmed rather than
        // throwing and taking the deletion down with it.
        String enormous = "x".repeat(4000);

        auditService.record("someone", AuditAction.ACCOUNT_DELETED,
                AuditAction.TARGET_ACCOUNT, 1L, enormous, enormous);

        assertThat(auditRepository.count())
                .as("it should have been written, trimmed")
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("no credential is ever written into an entry")
    void noSecretsInTheTrail() {
        userService.resetPassword(organization.getId(), member.getId());

        assertThat(entriesFor(member.getUsername()))
                .filteredOn(e -> AuditAction.ACCOUNT_PASSWORD_RESET_BY_ADMIN.equals(e.action()))
                .allSatisfy(e -> assertThat(e.detail())
                        .as("the generated password must not be in the audit trail")
                        .isNull());
    }
}
