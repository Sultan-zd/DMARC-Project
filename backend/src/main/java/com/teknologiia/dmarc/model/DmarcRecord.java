package com.teknologiia.dmarc.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity @Table(name = "dmarc_records")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class DmarcRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "report_id", nullable = false)
    @JsonIgnore
    private DmarcReport report;

    @Column(name = "source_ip", nullable = false, length = 45)
    private String sourceIp;

    @Column(nullable = false)
    @Builder.Default
    private int count = 0;

    private String disposition;

    @Column(name = "dkim_result")
    private String dkimResult;

    @Column(name = "spf_result")
    private String spfResult;

    @Column(name = "dkim_domain")
    private String dkimDomain;

    @Column(name = "spf_domain")
    private String spfDomain;

    @Column(name = "header_from")
    private String headerFrom;

    @Column(name = "envelope_from")
    private String envelopeFrom;

    @Column(name = "dkim_selector")
    private String dkimSelector;
}
