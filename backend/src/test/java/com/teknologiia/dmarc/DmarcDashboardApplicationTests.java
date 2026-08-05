package com.teknologiia.dmarc;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Boots the whole application context.
 *
 * <p>Runs under the {@code test} profile so it uses an in-memory database rather
 * than requiring a MariaDB server, and never writes to real data.
 */
@SpringBootTest
@ActiveProfiles("test")
class DmarcDashboardApplicationTests {

    @Test
    void contextLoads() {
    }

}
