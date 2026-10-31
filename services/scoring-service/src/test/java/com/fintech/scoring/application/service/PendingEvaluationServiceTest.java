package com.fintech.scoring.application.service;

import com.fintech.scoring.application.BureauReportPending;
import com.fintech.scoring.application.EvaluationNotReady;
import com.fintech.scoring.application.port.out.CirculoReportRepository;
import com.fintech.scoring.application.port.out.PendingEvaluationRepository;
import com.fintech.scoring.application.port.out.PendingEvaluationRepository.PendingEvaluation;
import com.fintech.scoring.domain.CirculoReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * El puntaje que llegó antes que el reporte espera, y el prefetch lo despierta.
 */
class PendingEvaluationServiceTest {

    private final PendingEvaluationRepository repository = mock(PendingEvaluationRepository.class);
    private final CirculoReportRepository reports = mock(CirculoReportRepository.class);
    private final ScoringEvaluationService evaluation = mock(ScoringEvaluationService.class);
    private PendingEvaluationService service;

    private final UUID prospect = UUID.randomUUID();
    private final UUID application = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PendingEvaluationService(repository, reports, evaluation);
        when(reports.findByProspectId(any())).thenReturn(Optional.empty());
    }

    private PendingEvaluation pending(UUID applicationId) {
        return new PendingEvaluation(applicationId, prospect, "INDIVIDUAL", "PERSONAL_LOAN", "NO_PREFETCH");
    }

    @Test
    @DisplayName("sin reporte todavía: se estaciona y nadie evalúa")
    void sinReporteSeEstaciona() {
        service.park(application, prospect, "INDIVIDUAL", "PERSONAL_LOAN", EvaluationNotReady.Reason.NO_PREFETCH);

        verify(repository).park(pending(application));
        verify(repository, never()).claimWaitingFor(any());
        verify(evaluation, never()).evaluateForApplication(any(), any(), any(), any());
    }

    @Test
    @DisplayName("🔑 el reporte aterrizó mientras se estacionaba: se reanuda ahí mismo, no se pierde el aviso")
    void elReporteLlegoAlEstacionar() {
        when(reports.findByProspectId(prospect)).thenReturn(Optional.of(mock(CirculoReport.class)));
        when(repository.claimWaitingFor(prospect)).thenReturn(List.of(pending(application)));

        service.park(application, prospect, "INDIVIDUAL", "PERSONAL_LOAN", EvaluationNotReady.Reason.NO_PREFETCH);

        verify(evaluation).evaluateForApplication(application, prospect, "INDIVIDUAL", "PERSONAL_LOAN");
    }

    @Test
    @DisplayName("al terminar el prefetch evalúa todo lo que esperaba a ese prospecto")
    void reanudaTodoLoQueEsperaba() {
        UUID otra = UUID.randomUUID();
        when(repository.claimWaitingFor(prospect)).thenReturn(List.of(pending(application), pending(otra)));

        service.resumeFor(prospect);

        verify(evaluation).evaluateForApplication(application, prospect, "INDIVIDUAL", "PERSONAL_LOAN");
        verify(evaluation).evaluateForApplication(otra, prospect, "INDIVIDUAL", "PERSONAL_LOAN");
    }

    @Test
    @DisplayName("si nadie la reclamó (ya la tomó el otro lado), no se evalúa dos veces")
    void loYaReclamadoNoSeEvalua() {
        when(repository.claimWaitingFor(prospect)).thenReturn(List.of());

        service.resumeFor(prospect);

        verify(evaluation, never()).evaluateForApplication(any(), any(), any(), any());
    }

    @Test
    @DisplayName("prefetch fallido: queda para intervención, no vuelve a esperar algo que no llegará")
    void prefetchFallidoNoVuelveAEsperar() {
        when(repository.claimWaitingFor(prospect)).thenReturn(List.of(pending(application)));
        when(evaluation.evaluateForApplication(any(), any(), any(), any()))
                .thenThrow(new EvaluationNotReady(EvaluationNotReady.Reason.PREFETCH_FAILED, "FAILED"));

        service.resumeFor(prospect);

        verify(repository, never()).park(any());
    }

    @Test
    @DisplayName("una falla inesperada al reanudar la devuelve a la espera, a la vista")
    void fallaInesperadaVuelveALaEspera() {
        when(repository.claimWaitingFor(prospect)).thenReturn(List.of(pending(application)));
        when(evaluation.evaluateForApplication(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("base caída"));

        service.resumeFor(prospect);

        verify(repository).park(argThat(p -> p.applicationId().equals(application)
                && p.reason().equals("RESUME_FAILED")));
    }

    @Test
    @DisplayName("reanudada sin reporte: vuelve a esperar sin girar en círculo")
    void reanudadaSinReporteVuelveAEsperar() {
        when(repository.claimWaitingFor(prospect)).thenReturn(List.of(pending(application)));
        when(evaluation.evaluateForApplication(any(), any(), any(), any()))
                .thenThrow(new BureauReportPending("sigue en vuelo"));

        service.resumeFor(prospect);

        verify(repository, times(1)).park(any());
        verify(repository, times(1)).claimWaitingFor(eq(prospect));
    }

    @Test
    @DisplayName("rendimiento: reanudar mil solicitudes no agrega costo propio")
    void reanudarMilEsBarato() {
        List<PendingEvaluation> mil = new ArrayList<>();
        IntStream.range(0, 1_000).forEach(i -> mil.add(pending(UUID.randomUUID())));
        when(repository.claimWaitingFor(prospect)).thenReturn(mil);

        long inicio = System.nanoTime();
        service.resumeFor(prospect);
        long ms = (System.nanoTime() - inicio) / 1_000_000;

        verify(evaluation, times(1_000)).evaluateForApplication(any(), any(), any(), any());
        assertThat(ms).isLessThan(2_000L);
    }
}
