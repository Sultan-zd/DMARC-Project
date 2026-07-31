package com.teknologiia.dmarc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DmarcDashboardApplication {

    public static void main(String[] args) {
        SpringApplication.run(DmarcDashboardApplication.class, args);
    }

}
