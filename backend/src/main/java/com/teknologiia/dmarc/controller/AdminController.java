package com.teknologiia.dmarc.controller;

import com.teknologiia.dmarc.dto.user.UserCreateRequest;
import com.teknologiia.dmarc.dto.user.UserResponse;
import com.teknologiia.dmarc.service.EmailService;
import com.teknologiia.dmarc.service.ExportService;
import com.teknologiia.dmarc.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AdminController {

    private final UserService userService;
    private final EmailService emailService;
    private final ExportService exportService;

    @GetMapping("/admin/users")
    public List<UserResponse> getUsers() {
        return userService.getAllUsers();
    }

    @PostMapping("/admin/users")
    public UserResponse createUser(@Valid @RequestBody UserCreateRequest request) {
        return userService.createUser(request);
    }

    @PostMapping("/admin/ingest")
    public Map<String, Object> ingest() {
        return emailService.fetchAndProcessEmails();
    }

    @GetMapping("/export/csv")
    public ResponseEntity<byte[]> exportCsv(
            @RequestParam(required = false) String domain,
            @RequestParam(name = "date_from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateFrom,
            @RequestParam(name = "date_to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateTo) {
        
        byte[] csvData = exportService.exportCsv(domain, dateFrom, dateTo);
        
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"export.csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csvData);
    }

    @GetMapping("/export/pdf")
    public ResponseEntity<byte[]> exportPdf(
            @RequestParam(required = false) String domain,
            @RequestParam(name = "date_from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateFrom,
            @RequestParam(name = "date_to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateTo) {
        
        byte[] pdfData = exportService.exportPdf(domain, dateFrom, dateTo);
        
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"export.pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdfData);
    }
}
