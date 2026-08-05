package com.teknologiia.dmarc.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Entity @Table(name = "domain_analyses")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class DomainAnalysis {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Owning tenant, or {@code null} for an anonymous scan from the public page.
     * Nullable on purpose: those scans predate any account and belong to nobody.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    @Column(nullable = false)
    private String domain;

    private int score;
    private String grade;

    @Column(name = "results_json", columnDefinition = "TEXT")
    private String resultsJson;

    @Column(name = "recommendations_json", columnDefinition = "TEXT")
    private String recommendationsJson;

    /**
     * Per-check score breakdown (DMARC, SPF, DKIM, MX, BIMI). Stored because it
     * cannot be recovered from the total alone, and a result read back from the
     * database — a shared link, or the history page — has to show it too.
     */
    @Column(name = "score_breakdown_json", columnDefinition = "TEXT")
    private String scoreBreakdownJson;

    @Column(name = "analyzed_at", updatable = false)
    @Builder.Default
    private LocalDateTime analyzedAt = LocalDateTime.now(ZoneOffset.UTC);

    @Column(name = "analyzed_by")
    private String analyzedBy;
}
