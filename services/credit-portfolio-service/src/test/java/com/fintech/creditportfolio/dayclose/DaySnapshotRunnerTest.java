package com.fintech.creditportfolio.dayclose;

import com.fintech.creditportfolio.application.port.out.DayCloseRepository;
import com.fintech.creditportfolio.application.port.out.DayCloseRepository.DayClose;
import com.fintech.creditportfolio.application.port.out.DaySnapshotRepository;
import com.fintech.creditportfolio.application.port.out.DaySnapshotRepository.Claim;
import com.fintech.creditportfolio.application.port.out.DaySnapshotRepository.DaySnapshot;
import com.fintech.creditportfolio.application.port.out.DaySnapshotRepository.Summary;
import com.fintech.creditportfolio.infrastructure.job.DaySnapshotRunner;
import com.fintech.creditportfolio.infrastructure.job.DaySnapshotRunner.Outcome;
import com.fintech.shared.kafka.ConsumerCatchUp;
import com.fintech.shared.time.BusinessClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The day snapshot: only for a day that ended, in order, with balances caught up, and once. */
class DaySnapshotRunnerTest {

    private static final ZoneId CDMX = ZoneId.of("America/Mexico_City");
    private static final LocalDate NINE = LocalDate.of(2026, 11, 9);
    private static final LocalDate TEN = NINE.plusDays(1);

    private final DaySnapshotRepository snapshots = mock(DaySnapshotRepository.class);
    private final DayCloseRepository dayCloses = mock(DayCloseRepository.class);
    private final ConsumerCatchUp catchUp = mock(ConsumerCatchUp.class);
    // 00:06 of the 11th in Mexico City: the 10th has ended.
    private final BusinessClock clock = new BusinessClock(Clock.fixed(Instant.parse("2026-11-11T06:06:00Z"), CDMX));
    private final DaySnapshotRunner runner = new DaySnapshotRunner(snapshots, dayCloses, catchUp, clock,
            Duration.ofHours(2), Duration.ofMillis(100), Duration.ofMillis(10), false, "credit-portfolio-service");

    @BeforeEach
    void gatesOpen() {
        when(snapshots.find(any(LocalDate.class))).thenReturn(Optional.empty());
        when(dayCloses.anyBefore(any())).thenReturn(true);
        when(dayCloses.find(NINE)).thenReturn(Optional.of(new DayClose(NINE, TEN, "DONE", "x", 1, 0, null, null, 1, Instant.now(), Instant.now())));
        when(catchUp.await(anyString(), any(), any(), any())).thenReturn(new ConsumerCatchUp.Result(true, 0, Duration.ZERO));
        when(snapshots.claim(eq(TEN), anyString(), any())).thenReturn(Claim.CLAIMED);
        when(snapshots.take(eq(TEN), any())).thenReturn(120);
        when(snapshots.summarize(TEN)).thenReturn(new Summary(120, 7, new BigDecimal("5000000.00"), new BigDecimal("12000.00")));
    }

    @Test
    @DisplayName("takes the snapshot of the 10th at the end of the 10th in Mexico City and records its totals")
    void takesTheSnapshotAtTheEndOfTheDay() {
        when(snapshots.find(TEN)).thenReturn(Optional.empty(), Optional.of(done(TEN)));

        var r = runner.take(TEN, "charges");

        assertThat(r.outcome()).isEqualTo(Outcome.TAKEN);
        verify(snapshots).take(TEN, LocalDate.of(2026, 11, 11).atStartOfDay(CDMX).toInstant());
        verify(snapshots).complete(TEN, new Summary(120, 7, new BigDecimal("5000000.00"), new BigDecimal("12000.00")));
    }

    @Test
    @DisplayName("a day in progress has no end-of-day snapshot")
    void refusesADayInProgress() {
        var r = runner.take(LocalDate.of(2026, 11, 11), "charges");

        assertThat(r.outcome()).isEqualTo(Outcome.WAITING);
        assertThat(r.detail()).contains("no ha terminado");
        verify(snapshots).waiting(eq(LocalDate.of(2026, 11, 11)), eq("charges"), anyString());
        verify(snapshots, never()).take(any(), any());
    }

    @Test
    @DisplayName("waits for the previous day to be closed in the portfolio: days go in order")
    void waitsForThePreviousDay() {
        when(dayCloses.find(NINE)).thenReturn(Optional.of(new DayClose(NINE, TEN, "WAITING", "x", null, null, null, "cifras", 2, Instant.now(), null)));

        var r = runner.take(TEN, "charges");

        assertThat(r.outcome()).isEqualTo(Outcome.WAITING);
        assertThat(r.detail()).contains("falta cerrar el 2026-11-09");
        verify(snapshots, never()).take(any(), any());
    }

    @Test
    @DisplayName("waits while payments or charges are still unconsumed: the balances would be stale")
    void waitsForBalancesToCatchUp() {
        when(catchUp.await(anyString(), any(), any(), any())).thenReturn(new ConsumerCatchUp.Result(false, 4, Duration.ofMillis(100)));

        var r = runner.take(TEN, "charges");

        assertThat(r.outcome()).isEqualTo(Outcome.WAITING);
        assertThat(r.detail()).contains("faltan 4 movimientos");
        verify(snapshots, never()).claim(any(), anyString(), any());
    }

    @Test
    @DisplayName("a snapshot already taken is only returned, never retaken")
    void doesNotRetake() {
        when(snapshots.find(TEN)).thenReturn(Optional.of(done(TEN)));

        var r = runner.take(TEN, "charges");

        assertThat(r.outcome()).isEqualTo(Outcome.ALREADY_TAKEN);
        assertThat(r.snapshot().accounts()).isEqualTo(120);
        verify(snapshots, never()).take(any(), any());
    }

    @Test
    @DisplayName("a failure is written down and the day stays retryable")
    void recordsFailures() {
        when(snapshots.take(eq(TEN), any())).thenThrow(new IllegalStateException("se cayó la base"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> runner.take(TEN, "charges"))
                .isInstanceOf(IllegalStateException.class);

        verify(snapshots).fail(TEN, "IllegalStateException: se cayó la base");
    }

    private static DaySnapshot done(LocalDate d) {
        return new DaySnapshot(d, "DONE", "charges", 120, 7, new BigDecimal("5000000.00"), new BigDecimal("12000.00"),
                null, null, 1, Instant.now(), Instant.now());
    }
}
