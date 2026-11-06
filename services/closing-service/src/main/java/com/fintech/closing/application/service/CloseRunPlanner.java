package com.fintech.closing.application.service;

import com.fintech.closing.application.ClosingProperties;
import com.fintech.closing.application.port.out.CloseRunRepository;
import com.fintech.closing.application.port.out.CloseUnitRepository;
import com.fintech.closing.domain.ClosePhase;
import com.fintech.closing.domain.CloseRun;
import com.fintech.closing.domain.CloseUnit;
import com.fintech.shared.lock.DistributedLock;
import com.fintech.shared.lock.LockKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Planifica una corrida: la crea y materializa sus unidades.
 *
 * <p><b>Un pod gana, los demás siguen de largo.</b> La exclusión la da el candado de Redis sobre
 * {@code closing:run:<fecha>:<fase>:<alcance>}; el que no lo obtiene no espera — se pone a trabajar
 * unidades que otro ya materializó. La restricción {@code uq_close_run} de la base queda como
 * segunda red: si el candado fallara, sigue siendo imposible tener dos corridas de la misma fase
 * y fecha.
 *
 * <p><b>La barrera de fase se comprueba aquí.</b> Una fase no se planifica si su predecesora no ha
 * sellado sobre la misma fecha y alcance. Es lo que sustituye a los offsets de reloj del as-is:
 * si el devengo se alarga, la mora espera en vez de leer datos viejos.
 */
@Service
public class CloseRunPlanner {

    private static final Logger log = LoggerFactory.getLogger(CloseRunPlanner.class);

    /** Planificar es rápido; el candado sólo cubre la materialización. */
    private static final Duration PLAN_LEASE = Duration.ofMinutes(10);

    private final CloseRunRepository runs;
    private final CloseUnitRepository units;
    private final DistributedLock lock;
    private final ClosingProperties properties;

    public CloseRunPlanner(CloseRunRepository runs, CloseUnitRepository units,
                            DistributedLock lock, ClosingProperties properties) {
        this.runs       = runs;
        this.units      = units;
        this.lock       = lock;
        this.properties = properties;
    }

    /** Lo que devuelve planificar: la corrida, y si este pod fue quien la creó. */
    public record Plan(CloseRun run, boolean createdByThisPod) {}

    /**
     * @param materializador produce las unidades de la corrida. Se le pasa la corrida ya creada
     *                       para que pueda insertar en lote con su {@code runId}.
     */
    public Optional<Plan> plan(LocalDate businessDate, ClosePhase phase, String scopeKey,
                                         Function<CloseRun, List<CloseUnit>> materializador) {
        return planBulk(businessDate, phase, scopeKey, run -> {
            List<CloseUnit> nuevas = materializador.apply(run);
            return nuevas.isEmpty() ? 0 : units.insertBatch(nuevas);
        });
    }

    /**
     * Igual que {@link #plan}, pero el materializador inserta él mismo y devuelve cuántas unidades
     * creó. Es la forma para universos grandes: un {@code INSERT … SELECT} en la base en vez de
     * traer cada cuenta a memoria para volverla a mandar.
     */
    public Optional<Plan> planBulk(LocalDate businessDate, ClosePhase phase, String scopeKey,
                                   ToIntFunction<CloseRun> materializador) {
        String alcance = scopeKey == null || scopeKey.isBlank() ? "ALL" : scopeKey;

        // Ya existe: nada que planificar, pero sí algo que trabajar.
        Optional<CloseRun> existente = runs.find(businessDate, phase, alcance);
        if (existente.isPresent()) {
            return Optional.of(new Plan(existente.get(), false));
        }

        savePhaseBarrier(businessDate, phase, alcance);

        LockKey key = LockKey.of("closing", "run", businessDate, phase, alcance);
        return lock.withLock(key, PLAN_LEASE, () -> {
            // Segunda comprobación con el candado tomado: entre la primera y ésta, otro pod pudo
            // haberla creado. Sin esto, el candado sólo estrecharía la ventana en vez de cerrarla.
            Optional<CloseRun> alreadyCreated = runs.find(businessDate, phase, alcance);
            if (alreadyCreated.isPresent()) {
                return new Plan(alreadyCreated.get(), false);
            }
            return new Plan(materialize(businessDate, phase, alcance, materializador), true);
        });
    }

    @Transactional
    protected CloseRun materialize(LocalDate businessDate, ClosePhase phase, String alcance,
                                     ToIntFunction<CloseRun> materializador) {
        CloseRun run = runs.save(CloseRun.plan(businessDate, phase, alcance));

        int insertadas = materializador.applyAsInt(run);

        run.materialized(insertadas);
        runs.save(run);

        log.info("Corrida planificada fase={} fecha={} alcance={} unidades={}",
                phase, businessDate, alcance, insertadas);
        return run;
    }

    /**
     * Una fase no arranca hasta que su predecesora selló.
     *
     * <p>Es la barrera para cuando el día lo orqueste este servicio. Hoy el corte no la usa: cargos y
     * cartera cierran el día en cadena desde las 00:05 y el corte arranca con el aviso de cartera.
     */
    private void savePhaseBarrier(LocalDate businessDate, ClosePhase phase, String alcance) {
        ClosePhase required = phase.requires();
        if (required == null) return;
        // El devengo y la mora los corren cargos y cartera, en cadena: nadie sella DELINQUENCY aquí y la
        // barrera dejaría el corte sin correr para siempre. El orden lo da el aviso de cartera.
        if (phase == ClosePhase.CUTOFF && !properties.getCutoff().isRequireDailyPhases()) return;

        boolean sellada = runs.find(businessDate, required, alcance)
                .map(CloseRun::isSealed)
                .orElse(false);

        if (!sellada) {
            throw new PhaseNotReady(phase, required, businessDate);
        }
    }

    /** La fase previa no ha sellado. No es un error del sistema: es que todavía no toca. */
    public static class PhaseNotReady extends IllegalStateException {
        public PhaseNotReady(ClosePhase phase, ClosePhase required, LocalDate businessDate) {
            super("La fase " + phase + " de " + businessDate + " no puede arrancar: "
                  + required + " todavía no ha sellado");
        }
    }
}
