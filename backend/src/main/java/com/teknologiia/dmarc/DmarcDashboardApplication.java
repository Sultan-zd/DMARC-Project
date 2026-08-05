package com.teknologiia.dmarc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
// Confirmation and invitation emails are sent off the request thread: an
// unreachable SMTP server must not make sign-up appear to hang.
@EnableAsync
public class DmarcDashboardApplication {

    public static void main(String[] args) {
        SpringApplication.run(DmarcDashboardApplication.class, args);
    }

}
