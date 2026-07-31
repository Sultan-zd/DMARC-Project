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

@Entity @Table(name = "dmarc_reports")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class DmarcReport {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "report_id", unique = true, nullable = false)
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
