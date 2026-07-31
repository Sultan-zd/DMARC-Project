package com.teknologiia.dmarc.config;

import com.teknologiia.dmarc.model.Alert;
import com.teknologiia.dmarc.model.DmarcRecord;
import com.teknologiia.dmarc.model.DmarcReport;
import com.teknologiia.dmarc.model.User;
import com.teknologiia.dmarc.repository.AlertRepository;
import com.teknologiia.dmarc.repository.DmarcReportRepository;
import com.teknologiia.dmarc.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@Component
public class DataInitializer implements ApplicationRunner {

    private final UserRepository userRepository;
    private final DmarcReportRepository reportRepository;
    private final AlertRepository alertRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.admin.username}")
    private String adminUsername;

    @Value("${app.admin.password}")
    private String adminPassword;

    public DataInitializer(UserRepository userRepository, DmarcReportRepository reportRepository, AlertRepository alertRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.reportRepository = reportRepository;
        this.alertRepository = alertRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!userRepository.existsByUsername(adminUsername)) {
            User admin = User.builder()
                    .username(adminUsername)
                    .email("admin@teknologiia.com")
                    .hashedPassword(passwordEncoder.encode(adminPassword))
                    .role("ADMIN")
                    .active(true)
                    .build();
            userRepository.save(admin);
        }

        if (reportRepository.count() == 0) {
            generateDemoData();
        }
    }

    private void generateDemoData() {
        if (!userRepository.existsByUsername("analyst1")) {
            userRepository.save(User.builder().username("analyst1").email("analyst1@demo.com").hashedPassword(passwordEncoder.encode("demo")).role("ANALYST").active(true).build());
        }
        if (!userRepository.existsByUsername("viewer1")) {
            userRepository.save(User.builder().username("viewer1").email("viewer1@demo.com").hashedPassword(passwordEncoder.encode("demo")).role("VIEWER").active(true).build());
        }

        Random random = new Random(42);
        String[] domains = {"example.com", "test.org"};
        String[] ips = {"192.168.1.1", "10.0.0.1", "172.16.0.1", "8.8.8.8"};
        
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        
        for (int i = 0; i < 60; i++) {
            for (String domain : domains) {
                DmarcReport report = DmarcReport.builder()
                        .reportId("rep-" + domain + "-" + i)
                        .orgName("Org " + domain)
                        .orgEmail("noreply@" + domain)
                        .dateBegin(now.minusDays(60 - i))
                        .dateEnd(now.minusDays(59 - i))
                        .domain(domain)
                        .adkim("r")
                        .aspf("r")
                        .policy("none")
                        .spPolicy("none")
                        .pct(100)
                        .build();

                List<DmarcRecord> records = new ArrayList<>();
                for (int j = 0; j < 3; j++) {
                    records.add(DmarcRecord.builder()
                            .report(report)
                            .sourceIp(ips[random.nextInt(ips.length)])
                            .count(random.nextInt(50) + 1)
                            .disposition(random.nextBoolean() ? "none" : "quarantine")
                            .dkimResult(random.nextBoolean() ? "pass" : "fail")
                            .spfResult(random.nextBoolean() ? "pass" : "fail")
                            .dkimDomain(domain)
                            .spfDomain(domain)
                            .headerFrom(domain)
                            .envelopeFrom(domain)
                            .build());
                }
                report.setRecords(records);
                reportRepository.save(report);
            }
        }
        
        alertRepository.save(Alert.builder().alertType("SPIKE").severity("HIGH").message("Spike detected").details("Detailed info").domain("example.com").read(false).build());
        alertRepository.save(Alert.builder().alertType("FAILURE_RATE").severity("CRITICAL").message("High failure rate").details("Detailed info").domain("test.org").read(false).build());
    }
}
