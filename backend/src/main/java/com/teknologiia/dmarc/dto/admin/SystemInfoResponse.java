package com.teknologiia.dmarc.dto.admin;

/**
 * What this deployment actually is, read at runtime rather than typed into the
 * interface.
 *
 * <p>The dashboard footer used to carry a hard-coded {@code v1.0.0} while the build
 * was on a different version entirely. Everything here comes from the running
 * process: its build metadata, its JVM, its live database connection and its
 * effective configuration.
 *
 * @param sessionMinutes      how long a sign-in lasts before the token expires
 * @param schemaAutoUpdate    whether Hibernate may alter the schema at startup. Fine
 *                            while building; in production a mistyped entity then
 *                            rewrites live tables without anyone asking
 * @param corsAllowsLocalhost whether a development origin is still permitted to call
 *                            this API
 * @param jwtSecretProvided   whether a signing key was configured; without one a
 *                            throwaway key is generated and every session ends at
 *                            restart
 */
public record SystemInfoResponse(
        String application,
        String version,
        String javaVersion,
        String database,
        String dnsResolver,
        long sessionMinutes,
        long invitationTtlHours,
        long verificationTtlHours,
        int scanRateCapacity,
        int scanRateRefillPerMinute,
        String maxUploadSize,
        boolean schemaAutoUpdate,
        boolean corsAllowsLocalhost,
        boolean jwtSecretProvided
) {}
