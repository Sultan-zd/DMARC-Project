package com.teknologiia.dmarc.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * An email domain an organization claims as its own.
 *
 * <p>Once verified, anyone signing up with an address at this domain joins that
 * organization instead of creating a new one — which is what stops five colleagues
 * from ending up in five separate silos of the same company.
 *
 * <p>The claim is worthless without proof, so it only takes effect after a DNS TXT
 * record is found under the domain. Otherwise anyone could claim a company they do
 * not own and absorb its employees' accounts as they sign up.
 */
@Entity
@Table(name = "organization_domains",
        uniqueConstraints = @UniqueConstraint(name = "uk_domain", columnNames = "domain"))
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class OrganizationDomain {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    /** Normalised, lowercase — the part after the @ in a member's address. */
    @Column(nullable = false, length = 253)
    private String domain;

    /** Value the owner must publish in DNS to prove control. */
    @Column(name = "verification_token", nullable = false, length = 80)
    private String verificationToken;

    /** Null until the DNS record has been seen. Only verified domains grant access. */
    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    /**
     * Role given to accounts that join through this domain. Deliberately the least
     * privileged by default: joining automatically should not confer the ability to
     * change anything.
     */
    @Column(name = "default_role", nullable = false, length = 20)
    @Builder.Default
    private String defaultRole = "VIEWER";

    @Column(name = "created_at", updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now(ZoneOffset.UTC);

    public boolean isVerified() {
        return verifiedAt != null;
    }

    /** The DNS name the token must appear under. */
    public String verificationHost() {
        return "_teknologiia-verify." + domain;
    }
}
