package com.fintech.closing.application.service.cutoff;

import com.fintech.closing.application.ClosingProperties;
import com.fintech.closing.application.port.out.CloseRunRepository;
import com.fintech.closing.application.port.out.ClosingEventPublisher;
import com.fintech.closing.application.port.out.CutoffUnitStore;
import com.fintech.closing.application.port.out.PortfolioDayCloseLog;
import com.fintech.closing.application.service.CloseRunPlanner;
import com.fintech.closing.domain.ClosePhase;
import com.fintech.closing.domain.CloseRun;
import com.fintech.shared.kafka.ConsumerCatchUp;
import com.fintech.shared.time.BusinessClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * El corte de un día, repartido por cuenta.
 *
 * <pre>
 *   job / operación ──► plan(D)
 *                         ├─ INSERT … SELECT: una unidad por cuenta que corta el día D
 *                         ├─ desglose por producto y titular  ──► closing.cutoff-run-planned
 *                         └─ despacho: una por cuenta          ──► closing.cutoff-due  (llave = cuenta)
 *
 *   cada pod del grupo ◄── closing.cutoff-due ── CAS ─► sella el corte ──► closing.cutoff-closed
 *
 *   monitor (una réplica) ── reaper · reintentos · reenvío ─► sin pendientes ─► SEALED | FAILED
 *                                                                          ──► closing.cutoff-run-completed
 * </pre>
 *
 * <p>Planificar es lo único serial, y es una sentencia SQL. Todo lo demás escala con particiones y
 * pods.
 */
@Service
public class CutoffRunService {

    private static final Logger log = LoggerFactory.getLogger(CutoffRunService.class);

    static final String SCOPE = "ALL";
    private static final int FAILURES_SHOWN = 20;

    /**
     * Lo que cartera publica y closing tiene que haber consumido antes de cortar: los saldos con que se
     * sella, la mora y las altas (una cuenta nueva sin perfil no entraría al corte).
     */
    static final List<String> PORTFOLIO_TOPICS = List.of("credit-portfolio.balance-updated",
            "credit-portfolio.delinquency-status-updated", "credit-portfolio.credit-account-activated");

    private final CloseRunPlanner planner;
    private final CloseRunRepository runs;
    private final CutoffUnitStore store;
    private final CutoffDispatcher dispatcher;
    private final CutoffRunCompletion completion;
    private final ClosingEventPublisher publisher;
    private final ClosingProperties properties;
    private final BusinessClock clock;
    private final PortfolioDayCloseLog dayCloses;
    private final ConsumerCatchUp catchUp;
    private final CutoffSnapshotAttacher snapshots;
    private final String consumerGroup;

    @Autowired
    public CutoffRunService(CloseRunPlanner planner, CloseRunRepository runs, CutoffUnitStore store,
                            CutoffDispatcher dispatcher, CutoffRunCompletion completion,
                            ClosingEventPublisher publisher, ClosingProperties properties, BusinessClock clock,
                            PortfolioDayCloseLog dayCloses, ConsumerCatchUp catchUp, CutoffSnapshotAttacher snapshots,
                            @Value("${spring.kafka.consumer.group-id:closing-service}") String consumerGroup) {
        this.snapshots     = snapshots;
        this.planner       = planner;
        this.runs          = runs;
        this.store         = store;
        this.dispatcher    = dispatcher;
        this.completion    = completion;
        this.publisher     = publisher;
        this.properties    = properties;
        this.clock         = clock;
        this.dayCloses     = dayCloses;
        this.catchUp       = catchUp;
        this.consumerGroup = consumerGroup;
    }

    /**
     * Corta, en orden, cada día que cartera ya cerró y closing no ha cortado, y se detiene en el primero
     * cuya compuerta no abra. Es lo que corren el aviso de cartera y la reanudación.
     */
    public List<CutoffRunView> advance(String trigger) {
        List<CutoffRunView> corridas = new ArrayList<>();
        for (LocalDate dia : dayCloses.pendingCutoffs()) {
            Optional<String> espera = gate(dia, properties.getCutoff().getGateWait());
            if (espera.isPresent()) {
                dayCloses.waiting(dia, espera.get());
                log.warn("Corte del {} en espera ({}): {}", dia, trigger, espera.get());
                break;
            }
            CutoffRunView corrida = plan(dia);
            dayCloses.planned(dia);
            corridas.add(corrida);
        }
        return corridas;
    }

    /**
     * El corte a mano (operación): por las mismas compuertas, pero sin esperar. Si no abren, dice por qué.
     *
     * @throws CutoffGateClosed si cartera no ha cerrado el día o closing no ha consumido sus saldos
     */
    public CutoffRunView planWhenReady(LocalDate businessDate) {
        Optional<String> espera = gate(businessDate, Duration.ZERO);
        if (espera.isPresent()) throw new CutoffGateClosed(businessDate, espera.get());
        CutoffRunView corrida = plan(businessDate);
        dayCloses.planned(businessDate);
        return corrida;
    }

    /** Vuelve a mandar el aviso de fin de los cortes que terminaron y cuyo aviso no salió. */
    public int announcePendingCompletions() {
        int n = 0;
        for (LocalDate dia : dayCloses.completedNotAnnounced()) {
            CloseRun run = runs.find(dia, ClosePhase.CUTOFF, SCOPE).orElse(null);
            if (run == null) continue;
            completion.announce(run, store.counts(run.getRunId()));
            n++;
        }
        return n;
    }

    /** Vacío si el corte de D puede planificarse; si no, por qué. */
    private Optional<String> gate(LocalDate d, Duration wait) {
        if (!dayCloses.closed(d)) {
            return Optional.of("cartera no ha cerrado el " + d + ": sin su cierre, el interés o la mora del día pueden faltar");
        }
        var consumo = catchUp.await(consumerGroup, PORTFOLIO_TOPICS, wait, properties.getCutoff().getGatePoll());
        if (!consumo.caughtUp()) {
            return Optional.of("faltan " + consumo.pending() + " movimientos de cartera por consumir: el corte sellaría saldos incompletos");
        }
        return Optional.empty();
    }

    /** Cómo va un corte: cuántas cuentas, de qué producto, cuántas van y cuáles fallaron. */
    public record CutoffRunView(UUID runId, LocalDate businessDate, String status, int plannedUnits,
                                CutoffUnitStore.StatusCounts counts, List<CutoffUnitStore.Breakdown> breakdown,
                                List<CutoffUnitStore.Failure> failures, Instant startedAt, Instant completedAt) {}

    /**
     * Planifica y despacha el corte del día. Idempotente: llamarlo dos veces no crea dos corridas,
     * y sobre una corrida existente sólo reenvía lo que no salió.
     *
     * @throws CutoffNotDue si el día todavía no termina
     */
    public CutoffRunView plan(LocalDate businessDate) {
        requireElapsed(businessDate);

        var plan = planner.planBulk(businessDate, ClosePhase.CUTOFF, SCOPE,
                run -> store.materialize(run.getRunId(), businessDate,
                        properties.getCutoff().getDistributorProductTypes(), dayCloses.heldAccounts(businessDate)));

        if (plan.isEmpty()) {
            // Otro pod tiene el candado de planificación y la está materializando ahora mismo.
            return view(businessDate).orElseThrow(() -> new IllegalStateException(
                    "El corte del " + businessDate + " se está planificando en otra réplica"));
        }

        CloseRun run = plan.get().run();
        if (plan.get().createdByThisPod()) {
            announce(run);
        }
        if (run.isRunning()) {
            // What the portfolio saw at the end of D, onto every unit before any pod seals one. Idempotent:
            // a unit that already has it is skipped; one that misses it asks for its own when sealed.
            snapshots.attach(run.getRunId(), businessDate);
            dispatcher.dispatch(run.getRunId(), businessDate);
            // Un día sin cortes sella en el acto; con cortes, lo cierra el monitor cuando se drene.
            completion.tryComplete(run);
        }
        return view(businessDate).orElseThrow();
    }

    /**
     * Lo que corre el job: todos los días con cortes vivos que no han corrido, del más viejo al de
     * ayer. Un pod caído o un cron perdido no dejan un hueco: la siguiente vuelta lo recoge.
     */
    public List<CutoffRunView> planDue(LocalDate today) {
        LocalDate desde = today.minusDays(properties.getCutoff().getLookbackDays());
        List<CutoffRunView> corridas = new ArrayList<>();
        for (LocalDate fecha : store.pendingCutoffDates(desde, today)) {
            corridas.add(plan(fecha));
        }
        if (corridas.isEmpty()) {
            log.info("Sin cortes pendientes entre {} y {}", desde, today.minusDays(1));
        }
        return corridas;
    }

    public Optional<CutoffRunView> view(LocalDate businessDate) {
        return runs.find(businessDate, ClosePhase.CUTOFF, SCOPE).map(this::view);
    }

    public CutoffRunView view(CloseRun run) {
        UUID runId = run.getRunId();
        return new CutoffRunView(runId, run.getBusinessDate(), run.status().name(), run.getPlannedUnits(),
                store.counts(runId), store.savedBreakdown(runId), store.failures(runId, FAILURES_SHOWN),
                run.getStartedAt(), run.getCompletedAt());
    }

    /**
     * Reintento manual de las fallidas: les devuelve los intentos y las vuelve a despachar.
     *
     * @throws IllegalStateException si el corte no existe o ya selló
     */
    public CutoffRunView retryFailed(LocalDate businessDate) {
        CloseRun run = runs.find(businessDate, ClosePhase.CUTOFF, SCOPE)
                .orElseThrow(() -> new IllegalStateException("No hay corte del " + businessDate));
        int devueltas = store.requeueAllFailed(run.getRunId());
        if (devueltas > 0 && !run.isRunning()) {
            run.reopen();
            runs.save(run);
        }
        log.info("Corte del {}: {} cuentas fallidas devueltas a la cola", businessDate, devueltas);
        dispatcher.dispatch(run.getRunId(), businessDate);
        return view(run);
    }

    /** Congela y publica cuántas cuentas entran, por producto y por titular. */
    private void announce(CloseRun run) {
        List<CutoffUnitStore.Breakdown> breakdown = store.breakdown(run.getRunId());
        store.saveBreakdown(run.getRunId(), breakdown);
        publisher.publishCutoffRunPlanned(run, breakdown);

        log.info("Corte del {} planificado: {} cuentas [{}] runId={}", run.getBusinessDate(), run.getPlannedUnits(),
                breakdown.stream()
                        .map(b -> b.holderKind() + "/" + b.productType() + "=" + b.units())
                        .collect(Collectors.joining(", ")),
                run.getRunId());
    }

    private void requireElapsed(LocalDate businessDate) {
        if (properties.getCutoff().isAllowFutureDates()) return;
        LocalDate hoy = clock.today();
        if (!businessDate.isBefore(hoy)) {
            throw new CutoffNotDue(businessDate, hoy);
        }
    }

    /** La compuerta del corte no abre: falta el cierre de cartera o closing va atrás con sus saldos. */
    public static class CutoffGateClosed extends IllegalStateException {
        public CutoffGateClosed(LocalDate businessDate, String reason) {
            super("El corte del " + businessDate + " no puede correr todavía: " + reason);
        }
    }

    /** El corte de un día sólo corre cuando el día terminó: antes sellaría un saldo que todavía se mueve. */
    public static class CutoffNotDue extends IllegalStateException {
        public CutoffNotDue(LocalDate businessDate, LocalDate today) {
            super("El corte del " + businessDate + " todavía no puede correr: el día de negocio es " + today
                  + " y un corte se sella cuando su día terminó");
        }
    }
}
