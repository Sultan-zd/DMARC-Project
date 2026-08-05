package com.teknologiia.dmarc.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * A DMARC aggregate report, owned by the organization that ingested it.
 *
 * <p>{@code report_id} is unique <em>per organization</em>, not globally: two
 * organizations legitimately receive the same provider report, and one importing it
 * must not make it look like a duplicate to the other.
 */
@Entity
@Table(name = "dmarc_reports",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_report_per_organization",
                columnNames = {"organization_id", "report_id"}))
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class DmarcReport {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Tenant owner. Every query for reports filters on this. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(name = "report_id", nullable = false)
    private String reportId;

    @Column(name = "org_name")
    private String orgName;

    @Column(name = "org_email")
    private String orgEmail;

    @Column(name = "date_begin")
    private LocalDateTime dateBegin;

    @Column(name = "date_end")
    private LocalDateTime dateEnd;

    @Column(nullable = false)
    private String domain;

    private String adkim;
    private String aspf;
    private String policy;

    @Column(name = "sp_policy")
    private String spPolicy;

    private Integer pct;

    @Column(name = "created_at", updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now(ZoneOffset.UTC);

    @OneToMany(mappedBy = "report", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<DmarcRecord> records = new ArrayList<>();
}
