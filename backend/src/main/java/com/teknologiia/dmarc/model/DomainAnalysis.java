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

    @Column(nullable = false)
    private String domain;

    private int score;
    private String grade;

    @Column(name = "results_json", columnDefinition = "TEXT")
    private String resultsJson;

    @Column(name = "recommendations_json", columnDefinition = "TEXT")
    private String recommendationsJson;

    @Column(name = "analyzed_at", updatable = false)
    @Builder.Default
    private LocalDateTime analyzedAt = LocalDateTime.now(ZoneOffset.UTC);

    @Column(name = "analyzed_by")
    private String analyzedBy;
}
