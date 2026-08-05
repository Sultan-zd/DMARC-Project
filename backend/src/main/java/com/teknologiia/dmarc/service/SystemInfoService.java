package com.teknologiia.dmarc.service;

import com.teknologiia.dmarc.dto.admin.SystemInfoResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Facts about the running deployment.
 *
 * <p>Read from the process itself, so the interface cannot claim a version, a
 * database or a session length this build does not have.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SystemInfoService {

    private final DataSource dataSource;
    /** Absent unless the build wrote build-info.properties. */
    private final Optional<BuildProperties> buildProperties;

    @Value("${spring.application.name:dmarc-dashboard}")
    private String applicationName;

    @Value("${app.jwt.expiration-ms:3600000}")
    private long jwtExpirationMs;

    @Value("${app.jwt.secret:}")
    private String jwtSecret;

    @Value("${app.invitation.ttl-hours:168}")
    private long invitationTtlHours;

    @Value("${app.registration.token-ttl-hours:24}")
    private long verificationTtlHours;

    @Value("${app.ratelimit.scan.capacity:10}")
    private int scanCapacity;

    @Value("${app.ratelimit.scan.refill-per-minute:5}")
    private int scanRefill;

    @Value("${spring.servlet.multipart.max-file-size:25MB}")
    private String maxUploadSize;

    @Value("${spring.jpa.hibernate.ddl-auto:validate}")
    private String ddlAuto;

    @Value("${app.cors.origins:}")
    private String corsOrigins;

    public SystemInfoResponse info() {
        return new SystemInfoResponse(
                applicationName,
                buildProperties.map(BuildProperties::getVersion).orElse("unknown"),
                System.getProperty("java.version"),
                databaseDescription(),
                // The analyser sends every lookup here; see DomainAnalysisService.
                ScoringModel.published().resolver(),
                jwtExpirationMs / 60_000,
                invitationTtlHours,
                verificationTtlHours,
                scanCapacity,
                scanRefill,
                maxUploadSize,
                // 'update' and 'create' both let Hibernate rewrite live tables.
                !"validate".equals(ddlAuto) && !"none".equals(ddlAuto),
                corsOrigins.contains("localhost") || corsOrigins.contains("127.0.0.1"),
                jwtSecret != null && !jwtSecret.isBlank());
    }

    /** Whatever the live connection reports, rather than what configuration implies. */
    private String databaseDescription() {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData meta = connection.getMetaData();
            return meta.getDatabaseProductName() + " " + meta.getDatabaseProductVersion();
        } catch (SQLException e) {
            log.warn("Could not read database metadata: {}", e.getMessage());
            return "unavailable";
        }
    }
}
