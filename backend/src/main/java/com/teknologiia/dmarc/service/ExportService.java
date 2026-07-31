package com.teknologiia.dmarc.service;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class ExportService {
    public ExportService() {
    }

    public byte[] exportCsv(String domain, LocalDateTime dateFrom, LocalDateTime dateTo) {
        // OpenCSV logic
        return new byte[0];
    }

    public byte[] exportPdf(String domain, LocalDateTime dateFrom, LocalDateTime dateTo) {
        // iText logic
        return new byte[0];
    }
}
