package com.teknologiia.dmarc.service;

import com.teknologiia.dmarc.dto.report.PaginatedResponse;
import com.teknologiia.dmarc.dto.report.ReportDetailResponse;
import com.teknologiia.dmarc.dto.report.ReportListResponse;
import com.teknologiia.dmarc.model.DmarcReport;
import com.teknologiia.dmarc.repository.DmarcReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class ReportService {

    private final DmarcReportRepository reportRepository;

    public PaginatedResponse<ReportListResponse> getReports(
            String domain, String orgName, String sourceIp, 
            LocalDateTime dateFrom, LocalDateTime dateTo, String policy,
            String sortBy, String sortOrder, int page, int size) {
        
        List<DmarcReport> allReports = reportRepository.findAll();
        if (domain != null && !domain.isEmpty()) {
            allReports = allReports.stream().filter(r -> r.getDomain().equalsIgnoreCase(domain)).collect(Collectors.toList());
        }

        List<ReportListResponse> list = allReports.stream().map(r -> new ReportListResponse(
                r.getId(), r.getReportId(), r.getOrgName(), r.getDateBegin(), r.getDateEnd(),
                r.getDomain(), r.getPolicy(), r.getRecords().size(),
                r.getRecords().stream().mapToInt(com.teknologiia.dmarc.model.DmarcRecord::getCount).sum(),
                r.getCreatedAt()
        )).collect(Collectors.toList());

        return new PaginatedResponse<>(list, list.size(), page, size, 1);
    }

    public ReportDetailResponse getReport(Long id) {
        DmarcReport r = reportRepository.findById(id).orElseThrow();
        return new ReportDetailResponse(
                r.getId(), r.getReportId(), r.getOrgName(), r.getOrgEmail(), r.getDateBegin(), r.getDateEnd(),
                r.getDomain(), r.getAdkim(), r.getAspf(), r.getPolicy(), r.getSpPolicy(), r.getPct(), r.getCreatedAt(),
                r.getRecords().stream().map(rec -> new com.teknologiia.dmarc.dto.report.RecordResponse(
                        rec.getId(), rec.getSourceIp(), rec.getCount(), rec.getDisposition(), rec.getDkimResult(), rec.getSpfResult(),
                        rec.getDkimDomain(), rec.getSpfDomain(), rec.getHeaderFrom(), rec.getEnvelopeFrom(), null
                )).collect(Collectors.toList())
        );
    }
}
