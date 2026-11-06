package com.fintech.creditportfolio.infrastructure.job;

import com.fintech.creditportfolio.application.port.out.DayCloseRepository;
import com.fintech.creditportfolio.application.port.out.DayCloseRepository.DayClose;
import com.fintech.creditportfolio.application.port.out.DaySnapshotRepository;
import com.fintech.creditportfolio.application.port.out.DaySnapshotRepository.Claim;
import com.fintech.creditportfolio.application.port.out.DaySnapshotRepository.DaySnapshot;
import com.fintech.creditportfolio.application.port.out.DaySnapshotRepository.Summary;
import com.fintech.shared.kafka.ConsumerCatchUp;
import com.fintech.shared.time.BusinessClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * The preliminary close of the day: a snapshot of every account at the end of D.
 *
 * <p>It is the first step of the nightly chain. Charges asks for it at 00:05 of D+1, before accruing D
 * ({@code POST /api/v1/portfolio/day-snapshots/D}), and accrues on top of it; closing cuts with it. That
 * way a day's interest and a cutoff's days past due do not depend on when the process ran or on whether
 * an account recovered late: every day has its own snapshot.
 *
 * <h2>Gates</h2>
 * <ol>
 *   <li><b>D has ended.</b> There is no "end of day" snapshot of a day in progress.</li>
 *   <li><b>D-1 is closed in the portfolio.</b> Days go in order; nobody skips one.</li>
 *   <li><b>Everything that moves balances has been consumed</b> (payments and charges) up to now.</li>
 * </ol>
 *
 * <p>When a gate is closed the day stays {@code WAITING} with the reason, and charges retries every 15
 * minutes. A snapshot is taken once; asking again only returns it.
 */
@Component
public class DaySnapshotRunner {

    private static final Logger log = LoggerFactory.getLogger(DaySnapshotRunner.class);

    /** What moves balances and the portfolio consumes from others: it must be up to date before the snapshot. */
    static final List<String> BALANCE_TOPICS = List.of(
            "payments.payment-applied", "payments.payment-returned", "charges.charge-applied", "charges.charge-reversed");

    public enum Outcome { TAKEN, ALREADY_TAKEN, WAITING, IN_PROGRESS }

    public record Result(LocalDate businessDate, Outcome outcome, String detail, DaySnapshot snapshot) {
        public boolean taken() { return outcome == Outcome.TAKEN || outcome == Outcome.ALREADY_TAKEN; }
    }

    private final DaySnapshotRepository snapshots;
    private final DayCloseRepository dayCloses;
    private final ConsumerCatchUp catchUp;
    private final BusinessClock clock;
    private final Duration staleAfter;
    private final Duration gateWait;
    private final Duration gatePoll;
    private final boolean allowFutureDates;
    private final String consumerGroup;

    @Autowired
    public DaySnapshotRunner(DaySnapshotRepository snapshots, DayCloseRepository dayCloses, ConsumerCatchUp catchUp,
                             BusinessClock clock,
                             @Value("${fintech.day-close.stale-after:PT2H}") Duration staleAfter,
                             @Value("${fintech.day-close.gate-wait:PT10M}") Duration gateWait,
                             @Value("${fintech.day-close.gate-poll:PT2S}") Duration gatePoll,
                             @Value("${fintech.day-close.allow-future-dates:false}") boolean allowFutureDates,
                             @Value("${spring.kafka.consumer.group-id:credit-portfolio-service}") String consumerGroup) {
        this.snapshots        = snapshots;
        this.dayCloses        = dayCloses;
        this.catchUp          = catchUp;
        this.clock            = clock;
        this.staleAfter       = staleAfter;
        this.gateWait         = gateWait;
        this.gatePoll         = gatePoll;
        this.allowFutureDates = allowFutureDates;
        this.consumerGroup    = consumerGroup;
    }

    public Result take(LocalDate businessDate, String triggeredBy) {
        Optional<DaySnapshot> done = snapshots.find(businessDate).filter(DaySnapshot::done);
        if (done.isPresent()) {
            return new Result(businessDate, Outcome.ALREADY_TAKEN, "foto tomada " + done.get().completedAt(), done.get());
        }
        Optional<String> wait = gate(businessDate);
        if (wait.isPresent()) {
            snapshots.waiting(businessDate, triggeredBy, wait.get());
            log.warn("Foto del {} en espera ({}): {}", businessDate, triggeredBy, wait.get());
            return new Result(businessDate, Outcome.WAITING, wait.get(), null);
        }
        Claim claim = snapshots.claim(businessDate, triggeredBy, staleAfter);
        if (claim == Claim.IN_PROGRESS) {
            return new Result(businessDate, Outcome.IN_PROGRESS, "otro proceso la está tomando", null);
        }
        if (claim == Claim.ALREADY_DONE) {
            DaySnapshot s = snapshots.find(businessDate).orElseThrow();
            return new Result(businessDate, Outcome.ALREADY_TAKEN, "foto tomada " + s.completedAt(), s);
        }

        long started = System.nanoTime();
        Summary summary;
        try {
            Instant endOfDay = businessDate.plusDays(1).atStartOfDay(clock.zone()).toInstant();
            snapshots.take(businessDate, endOfDay);
            summary = snapshots.summarize(businessDate);
            snapshots.complete(businessDate, summary);
        } catch (RuntimeException e) {
            snapshots.fail(businessDate, e.getClass().getSimpleName() + ": " + e.getMessage());
            throw e;
        }
        log.info("Foto del {} tomada ({}): {} cuentas, {} con atraso, capital {}, vencido {}, {} ms",
                businessDate, triggeredBy, summary.accounts(), summary.delinquentAccounts(),
                summary.principalTotal(), summary.overdueTotal(), (System.nanoTime() - started) / 1_000_000);
        if (summary.ledgerGaps() > 0) {
            // The subledger must explain every balance. A gap is a movement without an entry or an entry
            // whose balance update was lost; the account is listed in the snapshot (ledger_gap <> 0).
            log.warn("Foto del {}: {} cuentas cuyo libro (balance_events) no cuadra con sus saldos — revisar account_day_snapshots.ledger_gap",
                    businessDate, summary.ledgerGaps());
        }
        return new Result(businessDate, Outcome.TAKEN, summary.accounts() + " cuentas",
                snapshots.find(businessDate).orElseThrow());
    }

    /** Empty when the snapshot can be taken; otherwise why not. */
    private Optional<String> gate(LocalDate d) {
        if (!d.isBefore(clock.today()) && !allowFutureDates) {
            return Optional.of("el " + d + " no ha terminado: no hay foto al cierre de un día en curso");
        }
        LocalDate previous = d.minusDays(1);
        if (dayCloses.anyBefore(d) && dayCloses.find(previous).filter(DayClose::done).isEmpty()) {
            return Optional.of("falta cerrar el " + previous + " en cartera: los días van en orden");
        }
        var lag = catchUp.await(consumerGroup, BALANCE_TOPICS, gateWait, gatePoll);
        if (!lag.caughtUp()) {
            return Optional.of("faltan " + lag.pending() + " movimientos de saldo por consumir");
        }
        return Optional.empty();
    }
}
