package com.fintech.scoring.application.service;

import com.fintech.scoring.application.port.out.BureauPrefetchRepository;
import com.fintech.scoring.application.port.out.CirculoGateway;
import com.fintech.scoring.application.port.out.CirculoReportRepository;
import com.fintech.scoring.application.port.out.dto.CirculoQueryRequest;
import com.fintech.scoring.domain.BureauPrefetch;
import com.fintech.scoring.domain.BureauPrefetchStatus;
import com.fintech.scoring.domain.CirculoReport;
import com.fintech.scoring.domain.CirculoReportStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class BureauPrefetchServiceTest {

    @Mock BureauPrefetchRepository  prefetchRepository;
    @Mock CirculoReportRepository   reportRepository;
    @Mock CirculoGateway            circuloGateway;

    BureauPrefetchService service;

    final UUID prospectId  = UUID.randomUUID();
    final String curp      = "SEPJ650809HDFXXX01";
    final String consentRef= "event-id-001";

    @BeforeEach
    void setUp() {
        service = new BureauPrefetchService(prefetchRepository, reportRepository, circuloGateway);
        lenient().when(prefetchRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void initiate_noActivePrefetch_createsPrefetchAndCallsCirculo() {
        given(prefetchRepository.existsActiveByProspectId(prospectId)).willReturn(false);

        CirculoReport successReport = CirculoReport.builder(
                UUID.randomUUID(), UUID.randomUUID(), prospectId)
                .status(CirculoReportStatus.SUCCESS)
                .folioConsulta("31611250146")
                .ficoScoreValor(720)
                .build();
        given(circuloGateway.query(any(), any(), eq(prospectId), any())).willReturn(successReport);

        service.initiate(prospectId, curp, consentRef,
                "JUAN", "SESENTAYDOS", "PRUEBA", "SEPJ650809JG1",
                LocalDate.of(1965, 8, 9),
                "PASADISO 58", "58", null,
                "MONTEVIDEO", "GUSTAVO A MADERO", "CIUDAD DE MEXICO", "CDMX", "07730",
                "INDIVIDUAL", "PERSONAL_LOAN");

        // Prefetch saved twice: once IN_PROGRESS, once COMPLETED
        then(prefetchRepository).should(times(2)).save(any(BureauPrefetch.class));
        then(circuloGateway).should(times(1)).query(any(), any(), eq(prospectId), any());
        then(reportRepository).should(times(1)).save(any(CirculoReport.class));
    }

    @Test
    void initiate_activePrefetchExists_skips() {
        given(prefetchRepository.existsActiveByProspectId(prospectId)).willReturn(true);

        service.initiate(prospectId, curp, consentRef,
                "JUAN", "SESENTAYDOS", "PRUEBA", null,
                LocalDate.of(1965, 8, 9),
                "Calle 1", "10", null,
                "Col", null, "CDMX", "CDMX", "07730",
                "INDIVIDUAL", null);

        then(circuloGateway).should(never()).query(any(), any(), any(), any());
        then(prefetchRepository).should(never()).save(any());
    }

    @Test
    void initiate_circuloGatewayFails_marksPrefetchFailed() {
        given(prefetchRepository.existsActiveByProspectId(prospectId)).willReturn(false);
        given(circuloGateway.query(any(), any(), eq(prospectId), any()))
                .willThrow(new RuntimeException("connection refused"));

        service.initiate(prospectId, curp, consentRef,
                "JUAN", "SESENTAYDOS", "PRUEBA", null,
                LocalDate.of(1965, 8, 9),
                "Calle 1", "10", null,
                "Col", null, "CDMX", "CDMX", "07730",
                "INDIVIDUAL", null);

        ArgumentCaptor<BureauPrefetch> captor = ArgumentCaptor.forClass(BureauPrefetch.class);
        then(prefetchRepository).should(times(2)).save(captor.capture());

        BureauPrefetch lastSaved = captor.getAllValues().get(1);
        assertThat(lastSaved.getStatus()).isEqualTo(BureauPrefetchStatus.FAILED);
        assertThat(lastSaved.getFailureReason()).contains("connection refused");
    }

    @Test
    void initiate_circuloReturnsError_marksPrefetchFailed() {
        given(prefetchRepository.existsActiveByProspectId(prospectId)).willReturn(false);

        CirculoReport errorReport = CirculoReport.builder(
                UUID.randomUUID(), UUID.randomUUID(), prospectId)
                .status(CirculoReportStatus.ERROR)
                .errorCode("CDC_CALL_FAILED")
                .errorMessage("Service unavailable")
                .build();
        given(circuloGateway.query(any(), any(), eq(prospectId), any())).willReturn(errorReport);

        service.initiate(prospectId, curp, consentRef,
                "JUAN", "SESENTAYDOS", "PRUEBA", null,
                LocalDate.of(1965, 8, 9),
                "Calle 1", "10", null,
                "Col", null, "CDMX", "CDMX", "07730",
                "INDIVIDUAL", null);

        ArgumentCaptor<BureauPrefetch> captor = ArgumentCaptor.forClass(BureauPrefetch.class);
        then(prefetchRepository).should(times(2)).save(captor.capture());

        assertThat(captor.getAllValues().get(1).getStatus()).isEqualTo(BureauPrefetchStatus.FAILED);
    }

    @Test
    void initiate_successReport_marksCompleted_withoutEvaluating() {
        // ADR-001 Phase D: prefetch is prefetch-only — evaluation is deferred to ScoreRequested.
        given(prefetchRepository.existsActiveByProspectId(prospectId)).willReturn(false);
        CirculoReport successReport = CirculoReport.builder(
                UUID.randomUUID(), UUID.randomUUID(), prospectId)
                .status(CirculoReportStatus.SUCCESS)
                .ficoScoreValor(720)
                .build();
        given(circuloGateway.query(any(), any(), eq(prospectId), any())).willReturn(successReport);

        service.initiate(prospectId, curp, consentRef,
                "JUAN", "SESENTAYDOS", "PRUEBA", null,
                LocalDate.of(1965, 8, 9),
                "Calle 1", "10", null,
                "Col", null, "CDMX", "CDMX", "07730",
                "INDIVIDUAL", "PERSONAL_LOAN");

        ArgumentCaptor<BureauPrefetch> captor = ArgumentCaptor.forClass(BureauPrefetch.class);
        then(prefetchRepository).should(times(2)).save(captor.capture());
        assertThat(captor.getAllValues().get(1).getStatus()).isEqualTo(BureauPrefetchStatus.COMPLETED);
        then(reportRepository).should(times(1)).save(any(CirculoReport.class));
    }
}
