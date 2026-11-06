package com.fintech.charges.infrastructure.job;

import com.fintech.charges.application.port.out.AccrualDayRepository;
import com.fintech.charges.application.port.out.AccrualDayRepository.AccrualDay;
import com.fintech.charges.application.port.out.AccrualDayRepository.Claim;
import com.fintech.charges.application.port.out.AccrualUnitStore;
import com.fintech.charges.application.port.out.ChargeEventPublisher;
import com.fintech.charges.application.service.dayclose.AccrualDayMonitor;
import com.fintech.charges.application.service.dayclose.DayAnnouncer;
import com.fintech.charges.application.service.dayclose.DaySnapshotFetcher;
import com.fintech.shared.kafka.ConsumerCatchUp;
import com.fintech.shared.time.BusinessClock;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Aquí empieza el cierre del día: a partir de las <b>00:05 del día siguiente</b>, sobre el día que ya
 * terminó. El cierre del 10 de noviembre corre el 11 desde las 00:05.
 *
 * <h2>En orden, con compuertas</h2>
 *
 * La hora sólo despierta al job; lo que decide si un día arranca son las compuertas, y se revisan cada
 * vez, la dispare el cron, el soporte de pruebas o una reanudación:
 *
 * <ol>
 *   <li><b>El día terminó</b>: no antes de las 00:05 de D+1.</li>
 *   <li><b>El día anterior está cerrado aquí.</b> Los días arrancan en orden y sin huecos.</li>
 *   <li><b>Cartera cerró el día anterior.</b> El moratorio de D se cobra sobre la mora que mide ese cierre.</li>
 *   <li><b>Closing terminó el corte del día anterior.</b> La cadena queda en serie: cargos D → cartera D →
 *       corte D → cargos D+1.</li>
 *   <li><b>Cargos ya consumió todo lo que cartera publicó</b> (saldos y mora).</li>
 * </ol>
 *
 * <p>Si una compuerta no abre, el día queda {@code WAITING} con el motivo escrito y la siguiente vuelta
 * (cada 15 minutos) lo vuelve a intentar.
 *
 * <h2>Por cuenta</h2>
 *
 * Al pasar las compuertas, el día se reparte: una unidad por cuenta ({@code charges.accrual_units}), cada una
 * al tópico {@code charges.accrual-due}. Cualquier pod la devenga —ordinario y moratorio en una
 * transacción—, y con más pods el día termina antes. Una cuenta que falla se reintenta; agotados sus
 * intentos queda en cuarentena con su error, y el día cierra con las demás. Sus días siguientes nacen
 * retenidos: el interés de una cuenta nunca se adelanta a uno que le falta. Las cuentas que quedan fuera
 * viajan en el aviso del día: cartera cuadra sin ellas y closing retiene su corte.
 *
 * <p>El job espera en línea hasta {@code unit-wait} a que el día cierre; si no, lo termina el monitor
 * ({@link com.fintech.charges.application.service.dayclose.AccrualDayMonitor}).
 *
 * <p>Y vigila lo que ya avisó: si cartera no confirma un día en {@code reannounce-after}, vuelve a avisar; a
 * la segunda, reenvía antes los cargos del día —cartera los ignora si ya los tenía—.
 */
@Component
public class DayCloseAccrualJob {

    private static final Logger log = LoggerFactory.getLogger(DayCloseAccrualJob.class);

    /** Lo que cartera publica y cargos necesita tener antes de devengar: los saldos y la mora. */
    static final List<String> PORTFOLIO_TOPICS =
            List.of("credit-portfolio.balance-updated", "credit-portfolio.delinquency-status-updated");

    /** Cuántos días hacia atrás se vigila que cartera haya confirmado. */
    private static final int FOLLOW_UP_DAYS = 7;

    public enum Outcome { CLOSED, ALREADY_CLOSED, WAITING, IN_PROGRESS, FAILED }

    public record DayResult(LocalDate businessDate, Outcome outcome, String detail) {
        public boolean closed() { return outcome == Outcome.CLOSED || outcome == Outcome.ALREADY_CLOSED; }
    }

    /** Los tiempos del cierre; ver {@code fintech.day-close} en application.yml. */
    public record Settings(Duration notBefore, Duration gateWait, Duration gatePoll, Duration staleAfter,
                           Duration reannounceAfter, boolean allowFutureDates, String consumerGroup,
                           Duration unitWait) {}

    private final AccrualUnitStore units;
    private final AccrualDayMonitor monitor;
    private final DayAnnouncer announcer;
    private final ChargeEventPublisher publisher;
    private final AccrualDayRepository days;
    private final ConsumerCatchUp catchUp;
    private final DaySnapshotFetcher snapshots;
    private final BusinessClock clock;
    private final Settings settings;

    @Autowired
    public DayCloseAccrualJob(AccrualUnitStore units, AccrualDayMonitor monitor, DayAnnouncer announcer,
                              ChargeEventPublisher publisher, AccrualDayRepository days,
                              ConsumerCatchUp catchUp, DaySnapshotFetcher snapshots, BusinessClock clock,
                              @Value("${fintech.day-close.not-before:PT5M}") Duration notBefore,
                              @Value("${fintech.day-close.gate-wait:PT10M}") Duration gateWait,
                              @Value("${fintech.day-close.gate-poll:PT2S}") Duration gatePoll,
                              @Value("${fintech.day-close.stale-after:PT3H}") Duration staleAfter,
                              @Value("${fintech.day-close.reannounce-after:PT30M}") Duration reannounceAfter,
                              @Value("${fintech.day-close.allow-future-dates:false}") boolean allowFutureDates,
                              @Value("${spring.kafka.consumer.group-id:charges-service}") String consumerGroup,
                              @Value("${fintech.day-close.units.unit-wait:PT10M}") Duration unitWait) {
        this(units, monitor, announcer, publisher, days, catchUp, snapshots, clock, new Settings(notBefore, gateWait,
                gatePoll, staleAfter, reannounceAfter, allowFutureDates, consumerGroup, unitWait));
    }

    DayCloseAccrualJob(AccrualUnitStore units, AccrualDayMonitor monitor, DayAnnouncer announcer,
                       ChargeEventPublisher publisher, AccrualDayRepository days, ConsumerCatchUp catchUp,
                       DaySnapshotFetcher snapshots, BusinessClock clock, Settings settings) {
        this.units     = units;
        this.monitor   = monitor;
        this.announcer = announcer;
        this.publisher = publisher;
        this.days      = days;
        this.catchUp   = catchUp;
        this.snapshots = snapshots;
        this.clock     = clock;
        this.settings  = settings;
    }

    /** A las 00:05 y, mientras quede algo pendiente, cada 15 minutos. Sin pendientes no hace nada. */
    @SchedulerLock(name = "charges.day-close", lockAtMostFor = "PT3H", lockAtLeastFor = "PT30S")
    @Scheduled(cron = "${fintech.day-close.cron:0 5/15 * * * *}", zone = "${fintech.business.zone:America/Mexico_City}")
    public void run() {
        advance("cron");
    }

    /**
     * Cierra cada día pendiente, del más viejo a ayer, y se detiene en el primero que no pueda: el
     * siguiente nunca arranca antes que él.
     */
    public List<DayResult> advance(String trigger) {
        LocalDate ayer = clock.today().minusDays(1);
        Optional<LocalDate> ultimo = days.lastDone();
        if (ultimo.isEmpty()
                && !days.anyScheduleBornBefore(ayer.plusDays(1).atStartOfDay(clock.zone()).toInstant())) {
            // A platform with no account yet has no day to close. Closing "yesterday" empty, minutes after a fresh
            // start, made the first business day of a simulation the future of an already closed past, and the
            // E2E deleted those rows to start (2 Nov audit, M1).
            return List.of();
        }
        LocalDate desde = ultimo.map(d -> d.plusDays(1)).orElse(ayer);
        List<DayResult> hechos = new ArrayList<>();
        for (LocalDate d = desde; !d.isAfter(ayer); d = d.plusDays(1)) {
            DayResult r = closeDay(d, trigger);
            hechos.add(r);
            if (!r.closed()) break;
        }
        followUp(ayer);
        return hechos;
    }

    /**
     * Cierra los cargos de un día, si sus compuertas abren. Lo usa también el soporte de pruebas para
     * correr el reloj noche por noche: pasa por las mismas compuertas que el cron.
     */
    public DayResult closeDay(LocalDate businessDate, String trigger) {
        Optional<AccrualDay> previo = days.find(businessDate);
        if (previo.filter(AccrualDay::done).isPresent()) {
            announcer.announce(previo.get());
            return new DayResult(businessDate, Outcome.ALREADY_CLOSED, "ya estaba cerrado; se volvió a avisar");
        }

        Instant desde = businessDate.plusDays(1).atStartOfDay(clock.zone()).plus(settings.notBefore()).toInstant();
        if (!settings.allowFutureDates() && clock.now().isBefore(desde)) {
            return new DayResult(businessDate, Outcome.WAITING, "el día no ha terminado: su cierre corre desde " + desde);
        }

        // Ya repartido: sus compuertas pasaron al repartirlo. Sólo falta llevarlo hasta el final.
        if (previo.filter(AccrualDay::running).isPresent()) {
            return awaitDay(businessDate);
        }

        Optional<String> espera = gate(businessDate);
        if (espera.isPresent()) {
            days.waiting(businessDate, trigger, espera.get());
            log.warn("Cargos del {} en espera: {}", businessDate, espera.get());
            return new DayResult(businessDate, Outcome.WAITING, espera.get());
        }

        // The portfolio's snapshot of D is the basis of every accrual of D: without it nothing is planned.
        var foto = snapshots.fetch(businessDate);
        if (!foto.ready()) {
            days.waiting(businessDate, trigger, foto.detail());
            log.warn("Cargos del {} en espera: {}", businessDate, foto.detail());
            return new DayResult(businessDate, Outcome.WAITING, foto.detail());
        }

        Claim claim = days.claim(businessDate, trigger, settings.staleAfter());
        if (claim == Claim.ALREADY_DONE) {
            days.find(businessDate).ifPresent(announcer::announce);
            return new DayResult(businessDate, Outcome.ALREADY_CLOSED, "ya estaba cerrado; se volvió a avisar");
        }
        if (claim == Claim.CLAIMED) {
            try {
                Instant finDelDia = businessDate.plusDays(1).atStartOfDay(clock.zone()).toInstant();
                int nuevas = units.plan(businessDate, finDelDia);
                var cuentas = units.counts(businessDate);
                days.planned(businessDate, cuentas.total());
                log.info("Cargos del {} repartidos sobre la foto de {} cuentas: {} unidades ({} nuevas), {} retenidas por un día"
                         + " anterior sin cerrar, {} en cuarentena desde el reparto",
                        businessDate, foto.accounts(), cuentas.total(), nuevas, cuentas.held(), cuentas.quarantined());
            } catch (RuntimeException e) {
                days.fail(businessDate, e.getClass().getSimpleName() + ": " + e.getMessage());
                throw e;
            }
        }
        return awaitDay(businessDate);
    }

    /** Mueve el día hasta que cierre o se acabe {@code unit-wait}; lo que quede lo termina el monitor. */
    private DayResult awaitDay(LocalDate businessDate) {
        long limite = System.nanoTime() + settings.unitWait().toNanos();
        while (true) {
            Optional<String> abierto = monitor.drive(businessDate);
            AccrualDay dia = days.find(businessDate).orElseThrow();
            if (dia.done()) {
                int fuera = dia.heldUnits() == null ? 0 : dia.heldUnits();
                return new DayResult(businessDate, Outcome.CLOSED, dia.tallies().ordinarySchedules() + " cuentas, "
                        + dia.totals().ordinaryCharges() + " cargos ordinarios y " + dia.totals().moratoriumCharges()
                        + " moratorios" + (fuera > 0 ? "; " + fuera + " cuentas fuera del lote (cuarentena o retenidas)" : ""));
            }
            if (System.nanoTime() >= limite) {
                return new DayResult(businessDate, Outcome.IN_PROGRESS, abierto.orElse("en proceso"));
            }
            sleep(settings.gatePoll());
        }
    }

    /** Vacío si el día puede arrancar; si no, por qué. Espera hasta {@code gate-wait} lo que puede llegar. */
    private Optional<String> gate(LocalDate d) {
        long limite = System.nanoTime() + settings.gateWait().toNanos();
        LocalDate anterior = d.minusDays(1);

        // The first day this service closes has no previous day to wait for; from there on, none is skipped.
        // "First" means nothing closed before it: an empty day the cron closed later (a fresh environment whose
        // clock is then shifted back for a simulated run) does not make an earlier day wait for its predecessor.
        if (days.anyDoneBefore(d)) {
            if (days.find(anterior).filter(AccrualDay::done).isEmpty()) {
                return Optional.of("faltan los cargos del " + anterior + ": los días se cierran en orden");
            }
            while (!days.portfolioClosed(anterior)) {
                if (System.nanoTime() >= limite) {
                    return Optional.of("cartera no ha cerrado el " + anterior + ": el moratorio del " + d
                                       + " se cobra sobre la mora que mide ese cierre");
                }
                sleep(settings.gatePoll());
            }
            while (!days.cutoffCompleted(anterior)) {
                if (System.nanoTime() >= limite) {
                    return Optional.of("closing no ha terminado el corte del " + anterior
                                       + ": el día siguiente no arranca antes");
                }
                sleep(settings.gatePoll());
            }
        }

        Duration resta = Duration.ofNanos(Math.max(0, limite - System.nanoTime()));
        var consumo = catchUp.await(settings.consumerGroup(), PORTFOLIO_TOPICS, resta, settings.gatePoll());
        if (!consumo.caughtUp()) {
            return Optional.of("faltan " + consumo.pending() + " movimientos de cartera por consumir (saldos y mora):"
                               + " el devengo correría sobre saldos viejos");
        }
        return Optional.empty();
    }

    /** Vuelve a avisar lo que cartera no ha confirmado; a la segunda, reenvía antes los cargos. */
    private void followUp(LocalDate ayer) {
        Instant umbral = clock.now().minus(settings.reannounceAfter());
        for (AccrualDay dia : days.unconfirmed(ayer.minusDays(FOLLOW_UP_DAYS))) {
            if (dia.announcedAt() != null && dia.announcedAt().isAfter(umbral)) continue;
            if (dia.announcedAt() != null && dia.republishedAt() == null) {
                republish(dia.businessDate());
            }
            log.warn("Cartera no ha confirmado el cierre de cargos del {}: se vuelve a avisar", dia.businessDate());
            announcer.announce(dia);
        }
    }

    /**
     * Reenvía los cargos del devengo de un día con su mismo id. Cartera aplica sólo los que no tenía:
     * es lo que destraba un día cuyas cifras no cuadran porque un mensaje se descartó en el camino.
     */
    public int republish(LocalDate businessDate) {
        var cargos = days.accruedCharges(businessDate);
        cargos.forEach(c -> publisher.publishChargeApplied(c.chargeId().toString(), c.creditAccountId(),
                c.chargeType(), c.amount(), c.accrualDate(), businessDate));
        days.republished(businessDate);
        log.warn("Reenviados {} cargos del devengo del {}", cargos.size(), businessDate);
        return cargos.size();
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(Math.max(10, d.toMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
