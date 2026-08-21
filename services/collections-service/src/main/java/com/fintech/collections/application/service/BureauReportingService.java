package com.fintech.collections.application.service;

import com.fintech.collections.application.port.in.ListBureauReportsUseCase;
import com.fintech.collections.application.port.out.BureauReportRepository;
import com.fintech.collections.application.port.out.BureauReportingAdapter;
import com.fintech.collections.application.port.out.CollectionsEventPublisher;
import com.fintech.collections.domain.BureauEventType;
import com.fintech.collections.domain.BureauReport;
import com.fintech.collections.domain.BureauReportStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** BR-*: regulatory reporting of write-offs and partial forgiveness to the credit bureau. */
@Service
@Transactional
public class BureauReportingService implements ListBureauReportsUseCase {

    private static final Logger log = LoggerFactory.getLogger(BureauReportingService.class);

    private final BureauReportRepository reportRepository;
    private final BureauReportingAdapter reportingAdapter;
    private final CollectionsEventPublisher eventPublisher;

    public BureauReportingService(BureauReportRepository reportRepository,
                                   BureauReportingAdapter reportingAdapter,
                                   CollectionsEventPublisher eventPublisher) {
        this.reportRepository  = reportRepository;
        this.reportingAdapter  = reportingAdapter;
        this.eventPublisher    = eventPublisher;
    }

    @Override
    @Transactional(readOnly = true)
    public List<BureauReport> listPending() {
        return reportRepository.findByStatusIn(List.of(BureauReportStatus.PENDING, BureauReportStatus.FAILED));
    }

    /** BR-03: every WriteOffExecuted / CollectionAgreementExecuted(QUITA_PARCIAL) creates a PENDING report. BR-01: never twice. */
    public void createPendingReport(UUID creditAccountId, UUID obligorPartyId, BureauEventType eventType,
                                     UUID sourceRecordId, BigDecimal amount) {
        if (reportRepository.existsBySourceRecordId(sourceRecordId)) {
            log.info("BureauReport already exists for sourceRecordId={} — skipping", sourceRecordId);
            return;
        }
        BureauReport report = BureauReport.create(creditAccountId, obligorPartyId, eventType, sourceRecordId, amount);
        reportRepository.save(report);
        log.info("BureauReport created reportId={} eventType={} sourceRecordId={}",
                report.getReportId(), eventType, sourceRecordId);
    }

    /** BR-04/BR-05: nightly submission with indefinite retry — a regulatory obligation, not best-effort. */
    public void submitPending() {
        for (BureauReport report : listPending()) {
            try {
                String reference = reportingAdapter.submit(report.getCreditAccountId(), report.getObligorPartyId(),
                        report.getEventType(), report.getAmountReported());
                report.markSubmitted(reference);
                reportRepository.save(report);
                log.info("BureauReport submitted reportId={} reference={}", report.getReportId(), reference);
                eventPublisher.publishBureauReportSubmitted(report);
            } catch (Exception ex) {
                report.markFailed();
                reportRepository.save(report);
                log.error("BureauReport submission failed reportId={}: {} — will retry", report.getReportId(), ex.getMessage());
            }
        }
    }
}
