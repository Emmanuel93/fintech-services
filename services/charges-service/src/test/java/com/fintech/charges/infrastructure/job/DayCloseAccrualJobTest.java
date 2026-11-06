package com.fintech.charges.infrastructure.job;

import com.fintech.charges.application.port.out.AccrualDayRepository;
import com.fintech.charges.application.port.out.AccrualDayRepository.AccrualDay;
import com.fintech.charges.application.port.out.AccrualDayRepository.Claim;
import com.fintech.charges.application.port.out.AccrualDayRepository.ControlTotals;
import com.fintech.charges.application.port.out.AccrualDayRepository.Tallies;
import com.fintech.charges.application.port.out.AccrualUnitStore;
import com.fintech.charges.application.port.out.AccrualUnitStore.Counts;
import com.fintech.charges.application.port.out.ChargeEventPublisher;
import com.fintech.charges.application.service.dayclose.AccrualDayMonitor;
import com.fintech.charges.application.service.dayclose.DayAnnouncer;
import com.fintech.charges.application.service.dayclose.DaySnapshotFetcher;
import com.fintech.charges.infrastructure.job.DayCloseAccrualJob.Outcome;
import com.fintech.charges.infrastructure.job.DayCloseAccrualJob.Settings;
import com.fintech.shared.kafka.ConsumerCatchUp;
import com.fintech.shared.time.BusinessClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

/**
 * El cierre de cargos: desde las 00:05 de D+1, sobre D, sólo si sus compuertas abren y nunca saltándose
 * un día. Al pasar, reparte el día por cuenta y lo lleva hasta el final.
 */
class DayCloseAccrualJobTest {

    private static final ZoneId MX = ZoneId.of("America/Mexico_City");
    private static final LocalDate NOV_9 = LocalDate.of(2026, 11, 9);
    private static final LocalDate NOV_10 = LocalDate.of(2026, 11, 10);
    private static final ControlTotals TOTALS = new ControlTotals(79, new BigDecimal("1234.5600"), 6, new BigDecimal("88.1000"));

    private final AccrualUnitStore units = mock(AccrualUnitStore.class);
    private final AccrualDayMonitor monitor = mock(AccrualDayMonitor.class);
    private final DayAnnouncer announcer = mock(DayAnnouncer.class);
    private final ChargeEventPublisher publisher = mock(ChargeEventPublisher.class);
    private final AccrualDayRepository days = mock(AccrualDayRepository.class);
    private final ConsumerCatchUp catchUp = mock(ConsumerCatchUp.class);
    private final DaySnapshotFetcher snapshots = mock(DaySnapshotFetcher.class);

    @BeforeEach
    void upToDate() {
        when(catchUp.await(anyString(), any(), any(), any())).thenReturn(new ConsumerCatchUp.Result(true, 0, Duration.ZERO));
        // The portfolio's snapshot is there by default: the day is planned on top of it.
        when(snapshots.fetch(any())).thenReturn(DaySnapshotFetcher.Outcome.ready(80));
        when(days.claim(any(), anyString(), any())).thenReturn(Claim.CLAIMED);
        when(days.find(any())).thenReturn(Optional.empty());
        when(days.lastDone()).thenReturn(Optional.empty());
        when(days.anyDoneBefore(any())).thenReturn(false);
        // Accounts exist: a platform without any is the separate case below.
        lenient().when(days.anyScheduleBornBefore(any())).thenReturn(true);
        when(units.counts(any())).thenReturn(new Counts(0, 0, 80, 0, 0, 0));
        when(monitor.drive(any())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("without the portfolio's snapshot of the day nothing is planned: the day waits with the reason")
    void waitsForThePortfolioSnapshot() {
        when(snapshots.fetch(NOV_10)).thenReturn(DaySnapshotFetcher.Outcome.waiting(
                "cartera no ha tomado la foto del 2026-11-10: falta cerrar el 2026-11-09 en cartera"));

        var r = job("2026-11-11T06:06:00Z").closeDay(NOV_10, "cron");

        assertThat(r.outcome()).isEqualTo(Outcome.WAITING);
        assertThat(r.detail()).contains("foto del 2026-11-10");
        verify(days).waiting(eq(NOV_10), eq("cron"), eq(r.detail()));
        verify(days, never()).claim(any(), anyString(), any());
        verify(units, never()).plan(any(), any());
    }

    private DayCloseAccrualJob job(String utcInstant) {
        return job(utcInstant, Duration.ofSeconds(5));
    }

    private DayCloseAccrualJob job(String utcInstant, Duration unitWait) {
        return new DayCloseAccrualJob(units, monitor, announcer, publisher, days, catchUp, snapshots,
                new BusinessClock(Clock.fixed(Instant.parse(utcInstant), MX)),
                new Settings(Duration.ofMinutes(5), Duration.ofMillis(200), Duration.ofMillis(20), Duration.ofHours(3),
                        Duration.ofMinutes(30), false, "charges-service", unitWait));
    }

    private static AccrualDay day(LocalDate d, String status) {
        return new AccrualDay(d, status, "cron", 1, null, new Tallies(80, 0, 6, 0), TOTALS, null,
                Instant.parse("2026-11-11T06:05:00Z"), Instant.parse("2026-11-11T06:05:03Z"),
                Instant.parse("2026-11-11T06:05:03Z"), null, 80, 0);
    }

    /** Lo que deja el día: sin renglón al revisar las compuertas, cerrado al terminar de moverlo. */
    private void closesOnDrive(LocalDate d) {
        when(days.find(d)).thenReturn(Optional.empty(), Optional.of(day(d, "DONE")));
    }

    @Test
    @DisplayName("el 11 a las 00:05 reparte el 10 por cuenta y lo lleva hasta cerrarlo")
    void closesTheDayThatEnded() {
        closesOnDrive(NOV_10);

        // 00:05 en México = 06:05 UTC
        var r = job("2026-11-11T06:05:00Z").advance("cron");

        assertThat(r).extracting(DayCloseAccrualJob.DayResult::outcome).containsExactly(Outcome.CLOSED);
        InOrder order = inOrder(catchUp, days, units, monitor);
        order.verify(catchUp).await(eq("charges-service"), eq(DayCloseAccrualJob.PORTFOLIO_TOPICS), any(), any());
        order.verify(days).claim(eq(NOV_10), eq("cron"), any());
        // Las cuentas activadas después de la medianoche del 11 no devengan el 10.
        order.verify(units).plan(NOV_10, Instant.parse("2026-11-11T06:00:00Z"));
        order.verify(days).planned(NOV_10, 80);
        order.verify(monitor).drive(NOV_10);
    }

    @Test
    @DisplayName("a las 00:04 el día todavía no arranca, aunque lo pida alguien a mano")
    void notBefore0005() {
        var r = job("2026-11-11T06:04:00Z").closeDay(NOV_10, "test-support");

        assertThat(r.outcome()).isEqualTo(Outcome.WAITING);
        verify(units, never()).plan(any(), any());
    }

    @Test
    @DisplayName("si el 9 no está cerrado aquí, el 10 espera: los días no se saltan")
    void doesNotSkipADay() {
        when(days.lastDone()).thenReturn(Optional.of(LocalDate.of(2026, 11, 8)));
        when(days.anyDoneBefore(any())).thenReturn(true);

        var r = job("2026-11-11T06:05:00Z").closeDay(NOV_10, "cron");

        assertThat(r.outcome()).isEqualTo(Outcome.WAITING);
        assertThat(r.detail()).contains("faltan los cargos del " + NOV_9);
        verify(days).waiting(eq(NOV_10), eq("cron"), anyString());
        verify(units, never()).plan(any(), any());
    }

    @Test
    @DisplayName("cargos estuvo caído dos días: al volver cierra el 9 y después el 10, en ese orden")
    void catchesUpInOrder() {
        when(days.lastDone()).thenReturn(Optional.of(LocalDate.of(2026, 11, 8)));
        when(days.anyDoneBefore(any())).thenReturn(true);
        when(days.find(LocalDate.of(2026, 11, 8))).thenReturn(Optional.of(day(LocalDate.of(2026, 11, 8), "DONE")));
        when(days.find(NOV_9)).thenReturn(Optional.empty(), Optional.of(day(NOV_9, "DONE")), Optional.of(day(NOV_9, "DONE")));
        closesOnDrive(NOV_10);
        when(days.portfolioClosed(any())).thenReturn(true);
        when(days.cutoffCompleted(any())).thenReturn(true);

        var r = job("2026-11-11T06:05:00Z").advance("cron");

        assertThat(r).extracting(DayCloseAccrualJob.DayResult::businessDate).containsExactly(NOV_9, NOV_10);
        InOrder order = inOrder(units);
        order.verify(units).plan(eq(NOV_9), any());
        order.verify(units).plan(eq(NOV_10), any());
    }

    @Test
    @DisplayName("sin el cierre de cartera del 9, el 10 no arranca: su moratorio depende de esa mora")
    void waitsForPortfolio() {
        when(days.lastDone()).thenReturn(Optional.of(NOV_9));
        when(days.anyDoneBefore(any())).thenReturn(true);
        when(days.find(NOV_9)).thenReturn(Optional.of(day(NOV_9, "DONE")));

        var r = job("2026-11-11T06:05:00Z").closeDay(NOV_10, "cron");

        assertThat(r.outcome()).isEqualTo(Outcome.WAITING);
        assertThat(r.detail()).contains("cartera no ha cerrado el " + NOV_9);
        verify(units, never()).plan(any(), any());
    }

    @Test
    @DisplayName("con cartera cerrada pero el corte del 9 sin terminar, el 10 espera: la cadena va en serie")
    void waitsForCutoff() {
        when(days.lastDone()).thenReturn(Optional.of(NOV_9));
        when(days.anyDoneBefore(any())).thenReturn(true);
        when(days.find(NOV_9)).thenReturn(Optional.of(day(NOV_9, "DONE")));
        when(days.portfolioClosed(NOV_9)).thenReturn(true);

        var r = job("2026-11-11T06:05:00Z").closeDay(NOV_10, "cron");

        assertThat(r.outcome()).isEqualTo(Outcome.WAITING);
        assertThat(r.detail()).contains("closing no ha terminado el corte del " + NOV_9);
        verify(units, never()).plan(any(), any());
    }

    @Test
    @DisplayName("si cargos no ha consumido los últimos saldos de cartera, no devenga sobre saldos viejos")
    void waitsForBalances() {
        when(catchUp.await(anyString(), any(), any(), any())).thenReturn(new ConsumerCatchUp.Result(false, 42, Duration.ZERO));

        var r = job("2026-11-11T06:05:00Z").closeDay(NOV_10, "cron");

        assertThat(r.outcome()).isEqualTo(Outcome.WAITING);
        assertThat(r.detail()).contains("faltan 42 movimientos de cartera");
        verify(units, never()).plan(any(), any());
    }

    @Test
    @DisplayName("un día ya repartido no vuelve a pasar compuertas ni a planearse: sólo se lleva hasta el final")
    void plannedDayIsOnlyDriven() {
        when(days.find(NOV_10)).thenReturn(Optional.of(day(NOV_10, "RUNNING")), Optional.of(day(NOV_10, "DONE")));

        var r = job("2026-11-11T06:20:00Z").closeDay(NOV_10, "cron");

        assertThat(r.outcome()).isEqualTo(Outcome.CLOSED);
        verify(catchUp, never()).await(anyString(), any(), any(), any());
        verify(units, never()).plan(any(), any());
        verify(monitor).drive(NOV_10);
    }

    @Test
    @DisplayName("si las cuentas no terminan en la espera, regresa EN PROCESO con el motivo y lo termina el monitor")
    void returnsInProgressWhenWaitEnds() {
        when(days.find(NOV_10)).thenReturn(Optional.empty(), Optional.of(day(NOV_10, "RUNNING")));
        when(monitor.drive(NOV_10)).thenReturn(Optional.of("37 de 80 cuentas en proceso"));

        var r = job("2026-11-11T06:05:00Z", Duration.ofMillis(50)).closeDay(NOV_10, "cron");

        assertThat(r.outcome()).isEqualTo(Outcome.IN_PROGRESS);
        assertThat(r.detail()).isEqualTo("37 de 80 cuentas en proceso");
    }

    @Test
    @DisplayName("un día que cartera no confirmó: se vuelve a avisar y, a la segunda, se reenvían sus cargos")
    void followsUpWhatItAnnounced() {
        AccrualDay unconfirmed = day(NOV_9, "DONE");   // avisado a las 06:05 del 11; son las 07:00
        when(days.lastDone()).thenReturn(Optional.of(NOV_10));
        when(days.anyDoneBefore(any())).thenReturn(true);
        when(days.unconfirmed(any())).thenReturn(List.of(unconfirmed));
        when(days.accruedCharges(NOV_9)).thenReturn(List.of());

        job("2026-11-11T07:00:00Z").advance("cron");

        InOrder order = inOrder(days, announcer);
        order.verify(days).accruedCharges(NOV_9);
        order.verify(days).republished(NOV_9);
        order.verify(announcer).announce(unconfirmed);
    }

    @Test
    @DisplayName("rendimiento: revisar las compuertas de un día al corriente cuesta milisegundos")
    void gatesAreCheap() {
        when(days.lastDone()).thenReturn(Optional.of(NOV_10));
        when(days.anyDoneBefore(any())).thenReturn(true);
        DayCloseAccrualJob job = job("2026-11-11T06:05:00Z");
        long start = System.nanoTime();
        for (int i = 0; i < 1_000; i++) job.advance("cron");
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(2_000L);
    }

    @Test
    @DisplayName("a fresh platform with no account yet closes nothing: no empty day ahead of a simulation")
    void freshPlatformClosesNothing() {
        when(days.anyScheduleBornBefore(any())).thenReturn(false);

        assertThat(job("2026-11-11T06:06:00Z").advance("cron")).isEmpty();
        verify(days, never()).claim(any(), anyString(), any());
    }
}
