package com.fintech.scoring.application.service;

import com.fintech.scoring.application.port.out.BureauPrefetchRepository;
import com.fintech.scoring.application.port.out.CirculoGateway;
import com.fintech.scoring.application.port.out.CirculoReportRepository;
import com.fintech.scoring.application.port.out.dto.CirculoQueryRequest;
import com.fintech.scoring.domain.BureauPrefetch;
import com.fintech.scoring.domain.CirculoReport;
import com.fintech.scoring.domain.CirculoReportStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

@Service
public class BureauPrefetchService {

    private static final Logger log = LoggerFactory.getLogger(BureauPrefetchService.class);

    private final BureauPrefetchRepository  prefetchRepository;
    private final CirculoReportRepository   reportRepository;
    private final CirculoGateway            circuloGateway;

    public BureauPrefetchService(BureauPrefetchRepository prefetchRepository,
                                 CirculoReportRepository reportRepository,
                                 CirculoGateway circuloGateway) {
        this.prefetchRepository = prefetchRepository;
        this.reportRepository   = reportRepository;
        this.circuloGateway     = circuloGateway;
    }

    @Transactional
    public void initiate(UUID prospectId, String curp, String consentRef,
                         String firstName, String lastName1, String lastName2, String rfc,
                         LocalDate dateOfBirth,
                         String street, String exteriorNumber, String interiorNumber,
                         String neighborhood, String municipality, String city,
                         String state, String postalCode,
                         String prospectType, String productTypeIntent) {

        if (prefetchRepository.existsActiveByProspectId(prospectId)) {
            log.info("Active prefetch already exists for prospectId={} — skipping", prospectId);
            return;
        }

        UUID prefetchId = UUID.randomUUID();
        BureauPrefetch prefetch = BureauPrefetch.create(prefetchId, prospectId, curp, consentRef,
                prospectType, productTypeIntent);
        prefetch.markInProgress();
        prefetchRepository.save(prefetch);

        log.info("Initiating Círculo de Crédito prefetch prefetchId={} prospectId={} curp={}",
                prefetchId, prospectId, curp);

        CirculoQueryRequest request = new CirculoQueryRequest(
                lastName1, lastName2, firstName, curp, rfc, dateOfBirth,
                street, exteriorNumber, interiorNumber,
                neighborhood, municipality, city, state, postalCode);

        UUID reportId = UUID.randomUUID();
        CirculoReport report;
        try {
            report = circuloGateway.query(reportId, prefetchId, prospectId, request);
            reportRepository.save(report);
            if (report.getStatus() == CirculoReportStatus.SUCCESS) {
                prefetch.markCompleted();
                // ADR-001: prefetch-only. The scoring evaluation is deferred to ScoreRequested
                // (when a product is selected) — NOT triggered here at onboarding.
            } else {
                prefetch.markFailed(report.getErrorMessage());
            }
        } catch (Exception ex) {
            log.error("Círculo de Crédito prefetch failed prefetchId={} prospectId={}",
                    prefetchId, prospectId, ex);
            prefetch.markFailed(ex.getMessage());
        }

        prefetchRepository.save(prefetch);
        log.info("Círculo prefetch done prefetchId={} status={}", prefetchId, prefetch.getStatus());
    }
}
