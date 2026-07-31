package com.teknologiia.dmarc.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Entity @Table(name = "alerts")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class Alert {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "alert_type", nullable = false, length = 50)
    private String alertType;

    @Column(nullable = false, length = 20)
    private String severity;

    @Column(nullable = false, length = 500)
    private String message;

    @Column(columnDefinition = "TEXT")
    private String details;

    private String domain;

    @Column(name = "is_read")
    @Builder.Default
    private boolean read = false;

    @Column(name = "created_at", updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now(ZoneOffset.UTC);
}
