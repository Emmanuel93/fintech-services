package com.fintech.collections;

import com.fintech.collections.application.port.out.BureauReportRepository;
import com.fintech.collections.application.port.out.BureauReportingAdapter;
import com.fintech.collections.application.port.out.CollectionsEventPublisher;
import com.fintech.collections.application.service.BureauReportingService;
import com.fintech.collections.domain.BureauEventType;
import com.fintech.collections.domain.BureauReport;
import com.fintech.collections.domain.BureauReportStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class BureauReportingServiceTest {

    @Mock BureauReportRepository reportRepository;
    @Mock BureauReportingAdapter reportingAdapter;
    @Mock CollectionsEventPublisher eventPublisher;

    BureauReportingService service;

    private final UUID creditAccountId = UUID.randomUUID();
    private final UUID obligorPartyId  = UUID.randomUUID();
    private final UUID sourceRecordId  = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new BureauReportingService(reportRepository, reportingAdapter, eventPublisher);
    }

    @Test
    void createPendingReport_savesNewReport_whenNoneExistsForSource() {
        given(reportRepository.existsBySourceRecordId(sourceRecordId)).willReturn(false);
        given(reportRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.createPendingReport(creditAccountId, obligorPartyId, BureauEventType.WRITE_OFF,
                sourceRecordId, new BigDecimal("5000"));

        then(reportRepository).should().save(any());
    }

    @Test
    void createPendingReport_isIdempotent_whenReportAlreadyExists() {
        given(reportRepository.existsBySourceRecordId(sourceRecordId)).willReturn(true);

        service.createPendingReport(creditAccountId, obligorPartyId, BureauEventType.WRITE_OFF,
                sourceRecordId, new BigDecimal("5000"));

        then(reportRepository).should(never()).save(any());
    }

    @Test
    void submitPending_marksSubmitted_andPublishesEvent_onSuccess() {
        BureauReport report = BureauReport.create(creditAccountId, obligorPartyId, BureauEventType.WRITE_OFF,
                sourceRecordId, new BigDecimal("5000"));
        given(reportRepository.findByStatusIn(List.of(BureauReportStatus.PENDING, BureauReportStatus.FAILED)))
                .willReturn(List.of(report));
        given(reportingAdapter.submit(creditAccountId, obligorPartyId, BureauEventType.WRITE_OFF, new BigDecimal("5000")))
                .willReturn("BUREAU-REF-1");
        given(reportRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.submitPending();

        assertThat(report.getStatus()).isEqualTo(BureauReportStatus.SUBMITTED);
        assertThat(report.getBureauReference()).isEqualTo("BUREAU-REF-1");
        then(eventPublisher).should().publishBureauReportSubmitted(report);
    }

    @Test
    void submitPending_marksFailed_andContinues_whenAdapterThrows() {
        BureauReport failing = BureauReport.create(creditAccountId, obligorPartyId, BureauEventType.WRITE_OFF,
                sourceRecordId, new BigDecimal("5000"));
        BureauReport succeeding = BureauReport.create(creditAccountId, obligorPartyId, BureauEventType.QUITA_PARCIAL,
                UUID.randomUUID(), new BigDecimal("1000"));
        given(reportRepository.findByStatusIn(List.of(BureauReportStatus.PENDING, BureauReportStatus.FAILED)))
                .willReturn(List.of(failing, succeeding));
        given(reportingAdapter.submit(creditAccountId, obligorPartyId, BureauEventType.WRITE_OFF, new BigDecimal("5000")))
                .willThrow(new RuntimeException("bureau unavailable"));
        given(reportingAdapter.submit(creditAccountId, obligorPartyId, BureauEventType.QUITA_PARCIAL, new BigDecimal("1000")))
                .willReturn("BUREAU-REF-2");
        given(reportRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.submitPending();

        assertThat(failing.getStatus()).isEqualTo(BureauReportStatus.FAILED);
        assertThat(succeeding.getStatus()).isEqualTo(BureauReportStatus.SUBMITTED);
        then(eventPublisher).should().publishBureauReportSubmitted(succeeding);
        then(eventPublisher).should(never()).publishBureauReportSubmitted(failing);
    }

    /**
     * BR-01 relies on existsBySourceRecordId as a check-then-act guard. Simulates a race where
     * several threads deliver the same sourceRecordId and all see "not yet reported" (mocked to
     * return false) — in production the DB unique constraint on bureau_reports.source_record_id
     * rejects all but one; here we only verify the service layer doesn't panic under the race.
     */
    @Test
    void createPendingReport_concurrentDuplicateSourceRecord_serviceDoesNotPanic() throws InterruptedException {
        given(reportRepository.existsBySourceRecordId(sourceRecordId)).willReturn(false);
        given(reportRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        int threads = 4;
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<Throwable> unexpected = java.util.Collections.synchronizedList(new ArrayList<>());

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    gate.await();
                    service.createPendingReport(creditAccountId, obligorPartyId, BureauEventType.WRITE_OFF,
                            sourceRecordId, new BigDecimal("5000"));
                } catch (Throwable t) {
                    unexpected.add(t);
                } finally {
                    done.countDown();
                }
            });
        }

        gate.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        assertThat(unexpected).as("unexpected exceptions under concurrent load: %s", unexpected).isEmpty();
    }
}
