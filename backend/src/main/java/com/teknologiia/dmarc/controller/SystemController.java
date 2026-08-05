package com.teknologiia.dmarc.controller;

import com.teknologiia.dmarc.dto.admin.SystemInfoResponse;
import com.teknologiia.dmarc.service.SystemInfoService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What this deployment is running.
 *
 * <p>Signed-in callers only. The figures are harmless on their own but they do
 * describe the deployment's configuration, which is not something to hand to an
 * anonymous visitor.
 */
@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
public class SystemController {

    private final SystemInfoService systemInfoService;

    @GetMapping("/info")
    public SystemInfoResponse info() {
        return systemInfoService.info();
    }
}
