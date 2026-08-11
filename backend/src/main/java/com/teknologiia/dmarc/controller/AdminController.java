package com.teknologiia.dmarc.controller;

import com.teknologiia.dmarc.dto.admin.AdminOverviewResponse;
import com.teknologiia.dmarc.dto.ingest.IngestionResult;
import com.teknologiia.dmarc.service.AdminOverviewService;
import com.teknologiia.dmarc.service.EmailService;
import com.teknologiia.dmarc.service.ExportService;
import com.teknologiia.dmarc.service.ReportIngestionService;
import com.teknologiia.dmarc.repository.OrganizationRepository;
import com.teknologiia.dmarc.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AdminController {

    private final EmailService emailService;
    private final ExportService exportService;
    private final ReportIngestionService ingestionService;
    private final OrganizationRepository organizationRepository;
    private final AdminOverviewService overviewService;

    /** The operational picture of the caller's own organization. */
    @GetMapping("/admin/overview")
    public AdminOverviewResponse overview(@AuthenticationPrincipal AuthenticatedUser caller) {
        return overviewService.overview(caller.getOrganizationId());
    }

    @PostMapping("/admin/ingest")
    public IngestionResult ingest(@AuthenticationPrincipal AuthenticatedUser caller) {
        return emailService.fetchAndProcessEmails(caller.getOrganizationId());
    }

    /**
     * Accepts DMARC aggregate reports uploaded from the dashboard.
     *
     * <p>Each file may be a bare {@code .xml}, a gzipped {@code .xml.gz}, or a
     * {@code .zip} holding several of either. Malformed documents are reported in
     * the result rather than failing the whole upload.
     */
    @PostMapping(value = "/admin/reports/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<IngestionResult> uploadReports(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @RequestParam("files") List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            return ResponseEntity.badRequest().body(IngestionResult.empty());
        }

        // Uploaded reports are owned by the uploader's organization.
        var organization = organizationRepository.getReferenceById(caller.getOrganizationId());
        IngestionResult result = IngestionResult.empty();
        for (MultipartFile file : files) {
            if (file.isEmpty()) {
                continue;
            }
            try (InputStream content = file.getInputStream()) {
                result = result.merge(ingestionService.ingest(organization, file.getOriginalFilename(), content));
            } catch (IOException e) {
                result = result.merge(new IngestionResult(1, 0, 0, 0, 0,
                        List.of(file.getOriginalFilename() + ": upload could not be read")));
            }
        }

        // A run where nothing landed and everything errored is a failure, not a success.
        boolean nothingStored = result.reportsStored() == 0 && result.duplicatesSkipped() == 0;
        return nothingStored && result.hasErrors()
                ? ResponseEntity.unprocessableEntity().body(result)
                : ResponseEntity.ok(result);
    }

    @GetMapping("/export/csv")
    public ResponseEntity<byte[]> exportCsv(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @RequestParam(required = false) String domain,
            @RequestParam(name = "date_from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateFrom,
            @RequestParam(name = "date_to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateTo) {
        
        byte[] csvData = exportService.exportCsv(caller.getOrganizationId(), domain, dateFrom, dateTo);
        
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"export.csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csvData);
    }

    @GetMapping("/export/pdf")
    public ResponseEntity<byte[]> exportPdf(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @RequestParam(required = false) String domain,
            @RequestParam(name = "date_from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateFrom,
            @RequestParam(name = "date_to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateTo) {
        
        byte[] pdfData = exportService.exportPdf(caller.getOrganizationId(), domain, dateFrom, dateTo);
        
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"export.pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdfData);
    }
}
