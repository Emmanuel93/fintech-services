package com.fintech.charges.application.service.dayclose;

import com.fintech.charges.application.port.out.AccrualDayRepository;
import com.fintech.charges.application.port.out.AccrualDayRepository.AccrualDay;
import com.fintech.charges.application.port.out.AccrualDayRepository.ControlTotals;
import com.fintech.charges.application.port.out.AccrualDayRepository.Tallies;
import com.fintech.charges.application.port.out.AccrualUnitStore;
import com.fintech.charges.application.port.out.AccrualUnitStore.AccountTotals;
import com.fintech.charges.application.port.out.AccrualUnitStore.Counts;
import com.fintech.charges.application.port.out.AccrualUnitStore.LateUnit;
import com.fintech.charges.application.port.out.AccrualUnitStore.TroubleCounts;
import com.fintech.charges.application.port.out.ChargeEventPublisher;
import com.fintech.charges.application.port.out.ChargeEventPublisher.AccountAccrued;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** El monitor del cierre por cuenta: cierra el día cuando ya no queda nada en vuelo, y no antes. */
class AccrualDayMonitorTest {

    private static final LocalDate NOV_10 = LocalDate.of(2026, 11, 10);
    private static final ControlTotals TOTALS = new ControlTotals(97, new BigDecimal("410.2000"), 3, new BigDecimal("9.0000"));

    private final AccrualDayRepository days = mock(AccrualDayRepository.class);
    private final AccrualUnitStore units = mock(AccrualUnitStore.class);
    private final AccrualDispatcher dispatcher = mock(AccrualDispatcher.class);
    private final DayAnnouncer announcer = mock(DayAnnouncer.class);
    private final ChargeEventPublisher publisher = mock(ChargeEventPublisher.class);
    private final UnitSettings settings = new UnitSettings(Duration.ofMinutes(2), 3, Duration.ZERO,
            Duration.ofMinutes(2), 1000, 0.01, 10);
    private AccrualDayMonitor monitor;

    @BeforeEach
    void setUp() {
        monitor = new AccrualDayMonitor(days, units, dispatcher, announcer, publisher, settings, new SimpleMeterRegistry());
        when(days.find(NOV_10)).thenReturn(Optional.of(day("RUNNING")), Optional.of(day("DONE")));
        when(days.totalsFor(NOV_10)).thenReturn(TOTALS);
        when(days.complete(any(), any(), any(), anyInt())).thenReturn(true);
        when(units.quarantineExhausted(any(), anyInt())).thenReturn(List.of());
        when(units.troubleCounts()).thenReturn(new TroubleCounts(0, 0));
    }

    private static AccrualDay day(String status) {
        return new AccrualDay(NOV_10, status, "cron", 1, null, new Tallies(0, 0, 0, 0), TOTALS, null,
                Instant.parse("2026-11-11T06:05:00Z"), null, null, null, 100, null);
    }

    @Test
    @DisplayName("sin cuentas en vuelo cierra el día con las que quedaron fuera y lo avisa una vez")
    void completesAndAnnounces() {
        when(units.counts(NOV_10)).thenReturn(new Counts(0, 0, 97, 0, 2, 1));

        Optional<String> abierto = monitor.drive(NOV_10);

        assertThat(abierto).isEmpty();
        InOrder order = inOrder(units, dispatcher, days, announcer);
        order.verify(units).reap(NOV_10);
        order.verify(units).requeueFailed(eq(NOV_10), eq(3), any());
        order.verify(units).quarantineExhausted(NOV_10, 3);
        order.verify(dispatcher).dispatch(NOV_10);
        ArgumentCaptor<Tallies> cuentas = ArgumentCaptor.forClass(Tallies.class);
        order.verify(days).complete(eq(NOV_10), cuentas.capture(), eq(TOTALS), eq(3));
        order.verify(announcer).announce(any());
        assertThat(cuentas.getValue().ordinarySchedules()).isEqualTo(97);
        assertThat(cuentas.getValue().ordinaryErrors()).isEqualTo(3);
    }

    @Test
    @DisplayName("con cuentas en proceso el día sigue abierto y dice cuántas")
    void openUnitsKeepTheDayOpen() {
        when(units.counts(NOV_10)).thenReturn(new Counts(10, 5, 85, 0, 0, 0));

        Optional<String> abierto = monitor.drive(NOV_10);

        assertThat(abierto).contains("15 de 100 cuentas en proceso");
        verify(days, never()).complete(any(), any(), any(), anyInt());
        verify(announcer, never()).announce(any());
    }

    @Test
    @DisplayName("más cuarentenas de las que admite un día es una falla general: el día no cierra")
    void tooManyQuarantinedIsAGeneralFailure() {
        when(units.counts(NOV_10)).thenReturn(new Counts(0, 0, 50, 0, 50, 0));

        Optional<String> abierto = monitor.drive(NOV_10);

        assertThat(abierto).hasValueSatisfying(m -> assertThat(m).contains("50 cuentas en cuarentena").contains("falla general"));
        verify(days).note(eq(NOV_10), anyString());
        verify(days, never()).complete(any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("si otro pod ya lo cerró, esta vuelta no lo vuelve a avisar")
    void onlyOneCallAnnounces() {
        when(units.counts(NOV_10)).thenReturn(new Counts(0, 0, 100, 0, 0, 0));
        when(days.complete(any(), any(), any(), anyInt())).thenReturn(false);

        monitor.drive(NOV_10);

        verify(announcer, never()).announce(any());
    }

    @Test
    @DisplayName("las cuentas que devengaron tarde se avisan una por una, en orden de día; si el broker falla, se detiene")
    void announcesLateAccountsInOrder() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        var totales = new AccountTotals(1, new BigDecimal("0.0200"), 0, BigDecimal.ZERO);
        when(units.lateUnannounced(anyInt())).thenReturn(List.of(
                new LateUnit(NOV_10, a, totales), new LateUnit(NOV_10.plusDays(1), a, totales), new LateUnit(NOV_10, b, totales)));
        doThrow(new IllegalStateException("broker caído")).when(publisher)
                .publishAccountAccrued(new AccountAccrued(NOV_10, b, 1, new BigDecimal("0.0200"), 0, BigDecimal.ZERO));

        monitor.announceLate();

        InOrder order = inOrder(publisher, units);
        order.verify(publisher).publishAccountAccrued(new AccountAccrued(NOV_10, a, 1, new BigDecimal("0.0200"), 0, BigDecimal.ZERO));
        order.verify(units).lateAnnounced(NOV_10, a);
        order.verify(units).lateAnnounced(NOV_10.plusDays(1), a);
        verify(units, never()).lateAnnounced(NOV_10, b);
    }
}
