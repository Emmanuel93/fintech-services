package com.fintech.closing.application.service;

import com.fintech.closing.application.ClosingProperties;
import com.fintech.closing.application.port.in.CloseUnitProcessor;
import com.fintech.closing.application.port.out.AccountCloseProfileRepository;
import com.fintech.closing.application.port.out.CloseRunRepository;
import com.fintech.closing.application.port.out.CloseSealRepository;
import com.fintech.closing.application.port.out.CloseUnitRepository;
import com.fintech.closing.application.port.out.ClosingEventPublisher;
import com.fintech.closing.domain.ClosePhase;
import com.fintech.closing.domain.CloseRun;
import com.fintech.closing.domain.CloseSeal;
import com.fintech.closing.domain.CloseUnit;
import com.fintech.closing.domain.UnitStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.LocalDate;

/**
 * Corre un día de negocio completo: planifica cada fase, la trabaja y la sella.
 *
 * <p><b>El día se recibe, no se lee del reloj.</b> Es lo que permite reproducir el cierre del 15 el
 * día 20, sembrar historia y —sobre todo— probar el ciclo de vida entero de un crédito sin esperar
 * meses reales.
 *
 * <p>Las fases avanzan por dependencia y no por hora: si el devengo se alarga, la mora espera. Hoy
 * el orden es 23:00 → 23:30 → 23:59 → 01:00 y basta que el devengo tarde una hora para que la mora
 * corra sobre datos viejos <b>sin que nada lo detecte</b>.
 */
@Service
public class BusinessDayRunner {

    private static final Logger log = LoggerFactory.getLogger(BusinessDayRunner.class);

    private final CloseRunPlanner planner;
    private final CloseRunWorker worker;
    private final CloseRunRepository runs;
    private final CloseUnitRepository units;
    private final CloseSealRepository seals;
    private final AccountCloseProfileRepository profiles;
    private final ClosingEventPublisher publisher;
    private final ClosingProperties properties;
    private final Map<ClosePhase, CloseUnitProcessor> procesadores = new LinkedHashMap<>();

    public BusinessDayRunner(CloseRunPlanner planner, CloseRunWorker worker,
                              CloseRunRepository runs, CloseUnitRepository units,
                              CloseSealRepository seals, AccountCloseProfileRepository profiles,
                              ClosingEventPublisher publisher, ClosingProperties properties,
                              List<CloseUnitProcessor> disponibles) {
        this.planner    = planner;
        this.worker     = worker;
        this.runs       = runs;
        this.units      = units;
        this.seals      = seals;
        this.profiles   = profiles;
        this.publisher  = publisher;
        this.properties = properties;
        disponibles.forEach(p -> procesadores.put(p.phase(), p));
    }

    public record ResultadoDelDia(LocalDate businessDate, Map<ClosePhase, ResultadoDeFase> fases) {
        public boolean todoSellado() { return fases.values().stream().allMatch(ResultadoDeFase::sellada); }
    }

    public record ResultadoDeFase(int unidades, int hechas, int fallidas, int omitidas, boolean sellada) {}

    /**
     * Corre las fases diarias en orden, cada una tras el sello de la anterior.
     *
     * @param scopeKey el alcance. Partir el universo en varios alcances es la palanca de arriba
     *                 para acortar el tiempo: cada uno tiene su candado, su corrida y su sello.
     */
    public ResultadoDelDia runBusinessDay(LocalDate businessDate, String scopeKey) {
        Map<ClosePhase, ResultadoDeFase> resultados = new LinkedHashMap<>();

        for (ClosePhase fase : ClosePhase.dailyOrder()) {
            if (fase == ClosePhase.SEAL || fase == ClosePhase.PROPAGATE) continue;   // no son de unidades
            if (!procesadores.containsKey(fase) && fase != ClosePhase.RECONCILE) continue;

            resultados.put(fase, runPhase(businessDate, fase, scopeKey));
        }

        log.info("Día de negocio {} corrido alcance={} fases={}", businessDate, scopeKey, resultados.size());
        return new ResultadoDelDia(businessDate, resultados);
    }

    /** Una fase: planificar, trabajar hasta agotar, sellar. */
    public ResultadoDeFase runPhase(LocalDate businessDate, ClosePhase fase, String scopeKey) {
        var plan = planner.plan(businessDate, fase, scopeKey, run -> materializar(run, businessDate, fase));
        if (plan.isEmpty()) {
            // Otro pod tiene el candado de planificación. No es un fallo: se reintenta en el
            // siguiente tick y mientras tanto ese pod ya está materializando.
            return new ResultadoDeFase(0, 0, 0, 0, false);
        }

        CloseRun run = plan.get().run();
        if (run.isSealed()) {
            return new ResultadoDeFase(run.getPlannedUnits(), run.getDoneUnits(),
                    run.getFailedUnits(), run.getSkippedUnits(), true);
        }

        CloseUnitProcessor procesador = procesadores.get(fase);
        if (procesador != null) {
            trabajarHastaAgotar(run, businessDate, procesador);
        }

        int hechas   = units.countByStatus(run.getRunId(), UnitStatus.DONE.name());
        int fallidas = units.countByStatus(run.getRunId(), UnitStatus.FAILED.name());
        int omitidas = units.countByStatus(run.getRunId(), UnitStatus.SKIPPED.name());
        run.progress(hechas, fallidas, omitidas);

        boolean sellada = false;
        if (fallidas == 0) {
            run.seal();
            sellarCifras(businessDate, fase, scopeKey, hechas + omitidas);
            sellada = true;
        } else {
            log.warn("Fase {} de {} NO sella: {} unidades fallidas", fase, businessDate, fallidas);
        }
        runs.save(run);

        return new ResultadoDeFase(run.getPlannedUnits(), hechas, fallidas, omitidas, sellada);
    }

    /** Trabaja lotes hasta que no queden pendientes. El tope evita un bucle infinito si algo se atasca. */
    private void trabajarHastaAgotar(CloseRun run, LocalDate businessDate, CloseUnitProcessor procesador) {
        int vueltasMax = Math.max(10, run.getPlannedUnits() / Math.max(properties.getBatchSize(), 1) + 5);
        for (int i = 0; i < vueltasMax; i++) {
            var r = worker.workBatch(run.getRunId(), businessDate, procesador, 0);
            if (!r.huboTrabajo()) break;
        }
    }

    /**
     * Las cifras de control del sello, agregadas en SQL.
     *
     * <p>Recorrer las cuentas en memoria para sumarlas sería repetir el error que este servicio
     * existe para corregir — y en la parte serial del cierre, que es la que manda el techo.
     */
    private void sellarCifras(LocalDate businessDate, ClosePhase fase, String scopeKey, int unidades) {
        if (seals.find(businessDate, fase, scopeKey).isPresent()) return;   // idempotente

        var t = seals.aggregateActive();
        CloseSeal seal = seals.save(CloseSeal.of(businessDate, fase, scopeKey, unidades,
                t.principal(), t.interes(), t.penalidad(), t.deudaTotal()));
        publisher.publishDaySealed(seal);

        log.info("Sello {} {} alcance={} unidades={} deuda={}",
                fase, businessDate, scopeKey, unidades, t.deudaTotal());
    }

    /** Las unidades de la corrida: una por cuenta viva. */
    private List<CloseUnit> materializar(CloseRun run, LocalDate businessDate, ClosePhase fase) {
        List<CloseUnit> unidades = new ArrayList<>();
        for (var perfil : profiles.findActiveForClosing(businessDate, 100_000)) {
            unidades.add(CloseUnit.of(run.getRunId(), perfil.getCreditAccountId().toString(),
                    perfil.getCreditAccountId(), perfil.getProductType()));
        }
        return unidades;
    }
}
