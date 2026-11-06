package com.fintech.creditportfolio.dayclose;

import com.fintech.creditportfolio.application.port.out.CreditPortfolioEventPublisher;
import com.fintech.creditportfolio.application.port.out.DayCloseRepository;
import com.fintech.creditportfolio.application.port.out.DayCloseRepository.ChargesDay;
import com.fintech.creditportfolio.application.port.out.DayCloseRepository.ChargesTotals;
import com.fintech.creditportfolio.application.port.out.DayCloseRepository.Claim;
import com.fintech.creditportfolio.application.port.out.DayCloseRepository.DayClose;
import com.fintech.creditportfolio.application.port.out.DaySnapshotRepository;
import com.fintech.creditportfolio.application.port.out.DaySnapshotRepository.DaySnapshot;
import com.fintech.creditportfolio.domain.event.DayClosedEvent;
import com.fintech.creditportfolio.infrastructure.job.DayCloseRunner;
import com.fintech.creditportfolio.infrastructure.job.DayCloseRunner.Outcome;
import com.fintech.creditportfolio.infrastructure.job.DelinquencyCalculationJob;
import com.fintech.creditportfolio.infrastructure.job.InstallmentDueJob;
import com.fintech.creditportfolio.infrastructure.job.UpcomingInstallmentJob;
import com.fintech.shared.kafka.ConsumerCatchUp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** El cierre de cartera: sólo con el interés del día completo, en orden, y una vez. */
class DayCloseRunnerTest {

    private static final LocalDate NUEVE = LocalDate.of(2026, 11, 9);
    private static final LocalDate DIEZ = NUEVE.plusDays(1);
    private static final LocalDate ONCE = DIEZ.plusDays(1);
    private static final ChargesTotals AVISADO = new ChargesTotals(80, new BigDecimal("1000.5000"), 6, new BigDecimal("40.0000"));

    private final DayCloseRepository dayCloses = mock(DayCloseRepository.class);
    private final DaySnapshotRepository snapshots = mock(DaySnapshotRepository.class);
    private final DelinquencyCalculationJob delinquency = mock(DelinquencyCalculationJob.class);
    private final UpcomingInstallmentJob upcoming = mock(UpcomingInstallmentJob.class);
    private final InstallmentDueJob due = mock(InstallmentDueJob.class);
    private final CreditPortfolioEventPublisher publisher = mock(CreditPortfolioEventPublisher.class);
    private final ConsumerCatchUp catchUp = mock(ConsumerCatchUp.class);
    private final DayCloseRunner runner = new DayCloseRunner(dayCloses, snapshots, delinquency, upcoming, due, publisher, catchUp,
            Duration.ofHours(2), Duration.ofMillis(100), Duration.ofMillis(10), "credit-portfolio-service");

    @BeforeEach
    void cargosAvisoYCuadra() {
        when(dayCloses.find(any())).thenReturn(Optional.empty());
        // The day snapshot exists by default: charges accrued on top of it.
        when(snapshots.find(any(LocalDate.class))).thenReturn(Optional.of(new DaySnapshot(
                DIEZ, "DONE", "charges", 83, 5, BigDecimal.TEN, BigDecimal.ONE, null, null, 1, Instant.now(), Instant.now())));
        when(dayCloses.chargesDay(DIEZ)).thenReturn(Optional.of(new ChargesDay(DIEZ, AVISADO, Instant.now())));
        when(dayCloses.anyBefore(any())).thenReturn(false);
        when(catchUp.await(anyString(), any(), any(), any())).thenReturn(new ConsumerCatchUp.Result(true, 0, Duration.ZERO));
        when(dayCloses.appliedInterest(eq(DIEZ), any())).thenReturn(AVISADO);
        when(dayCloses.claim(eq(DIEZ), eq(ONCE), anyString(), any())).thenReturn(Claim.CLAIMED);
        when(delinquency.runFor(ONCE)).thenReturn(new DelinquencyCalculationJob.Tally(83, 1));
    }

    @Test
    @DisplayName("con el interés del 10 completo, cierra el 10: mora al 11, cuotas y después el aviso")
    void ordenYFechas() {
        assertThat(runner.close(DIEZ, "charges.day-accrued").outcome()).isEqualTo(Outcome.CLOSED);

        InOrder orden = inOrder(catchUp, dayCloses, delinquency, upcoming, due, publisher);
        orden.verify(catchUp).await(eq("credit-portfolio-service"), any(), any(), any());
        orden.verify(dayCloses).appliedInterest(eq(DIEZ), any());
        orden.verify(delinquency).runFor(ONCE);
        orden.verify(upcoming).runFor(ONCE);
        orden.verify(due).runFor(ONCE);
        orden.verify(dayCloses).complete(DIEZ, 83, 1);
        ArgumentCaptor<DayClosedEvent> aviso = ArgumentCaptor.forClass(DayClosedEvent.class);
        orden.verify(publisher).publishDayClosed(aviso.capture());
        assertThat(aviso.getValue().businessDate()).isEqualTo(DIEZ);
        assertThat(aviso.getValue().delinquencyAsOf()).isEqualTo(ONCE);
    }

    @Test
    @DisplayName("sin el aviso de cargos no cierra: sería cerrar sin interés")
    void sinAvisoDeCargos() {
        when(dayCloses.chargesDay(DIEZ)).thenReturn(Optional.empty());

        var r = runner.close(DIEZ, "test-support");

        assertThat(r.outcome()).isEqualTo(Outcome.WAITING);
        assertThat(r.detail()).contains("cargos no ha avisado");
        verify(dayCloses).waiting(eq(DIEZ), eq(ONCE), eq("test-support"), anyString());
        verifyNoInteractions(delinquency, publisher);
    }

    @Test
    @DisplayName("si cartera no ha consumido todos los cargos, espera")
    void faltanCargosPorConsumir() {
        when(catchUp.await(anyString(), any(), any(), any())).thenReturn(new ConsumerCatchUp.Result(false, 12, Duration.ofMillis(100)));

        var r = runner.close(DIEZ, "charges.day-accrued");

        assertThat(r.outcome()).isEqualTo(Outcome.WAITING);
        assertThat(r.detail()).contains("faltan 12 cargos");
        verifyNoInteractions(delinquency, publisher);
    }

    @Test
    @DisplayName("si las cifras no cuadran —un interés se perdió—, no cierra y dice cuánto falta")
    void cifrasQueNoCuadran() {
        when(dayCloses.appliedInterest(eq(DIEZ), any())).thenReturn(new ChargesTotals(79, new BigDecimal("988.0000"), 6, new BigDecimal("40.0000")));

        var r = runner.close(DIEZ, "charges.day-accrued");

        assertThat(r.outcome()).isEqualTo(Outcome.WAITING);
        assertThat(r.detail()).contains("80 ordinarios por 1000.50").contains("cartera tiene 79 por 988.00");
        verifyNoInteractions(delinquency, publisher);
    }

    @Test
    @DisplayName("si el 9 no cerró, el 10 espera: los días se cierran en orden")
    void enOrden() {
        when(dayCloses.anyBefore(DIEZ)).thenReturn(true);

        var r = runner.close(DIEZ, "charges.day-accrued");

        assertThat(r.outcome()).isEqualTo(Outcome.WAITING);
        assertThat(r.detail()).contains("falta cerrar el 2026-11-09");
        verifyNoInteractions(delinquency);
    }

    @Test
    @DisplayName("avanzar cierra los días avisados en orden y se detiene en el primero que espera")
    void avanzaEnOrden() {
        when(dayCloses.announcedNotClosed()).thenReturn(List.of(NUEVE, DIEZ));
        when(dayCloses.chargesDay(NUEVE)).thenReturn(Optional.empty());   // el 9 no cuadra aún

        var r = runner.advance("reanudación");

        assertThat(r).extracting(DayCloseRunner.Result::businessDate).containsExactly(NUEVE);
        verify(dayCloses, never()).claim(eq(DIEZ), any(), anyString(), any());
    }

    @Test
    @DisplayName("un día ya cerrado sólo vuelve a avisar: no recalcula la mora")
    void yaCerrado() {
        when(dayCloses.find(DIEZ)).thenReturn(Optional.of(new DayClose(DIEZ, ONCE, "DONE", "charges.day-accrued",
                83, 0, null, null, 1, Instant.now(), Instant.now())));

        assertThat(runner.close(DIEZ, "reanudación").outcome()).isEqualTo(Outcome.ALREADY_CLOSED);

        verifyNoInteractions(delinquency, upcoming, due, catchUp);
        verify(publisher).publishDayClosed(any());
    }

    @Test
    @DisplayName("si otro lo está cerrando, no hace nada")
    void enCurso() {
        when(dayCloses.claim(any(), any(), anyString(), any())).thenReturn(Claim.IN_PROGRESS);

        assertThat(runner.close(DIEZ, "charges.day-accrued").outcome()).isEqualTo(Outcome.IN_PROGRESS);

        verifyNoInteractions(delinquency, upcoming, due, publisher);
    }

    @Test
    @DisplayName("si la mora falla, el día queda FAILED con la causa y no se avisa a closing")
    void falla() {
        when(delinquency.runFor(ONCE)).thenThrow(new IllegalStateException("sin conexión"));

        assertThatThrownBy(() -> runner.close(DIEZ, "charges.day-accrued")).hasMessageContaining("sin conexión");

        verify(dayCloses).fail(eq(DIEZ), org.mockito.ArgumentMatchers.contains("sin conexión"));
        verify(publisher, never()).publishDayClosed(any());
    }
}
