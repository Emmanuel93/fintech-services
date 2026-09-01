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
    public record Planificacion(CloseRun run, boolean creadaPorEstePod) {}

    /**
     * @param materializador produce las unidades de la corrida. Se le pasa la corrida ya creada
     *                       para que pueda insertar en lote con su {@code runId}.
     */
    public Optional<Planificacion> plan(LocalDate businessDate, ClosePhase phase, String scopeKey,
                                         Function<CloseRun, List<CloseUnit>> materializador) {
        String alcance = scopeKey == null || scopeKey.isBlank() ? "ALL" : scopeKey;

        // Ya existe: nada que planificar, pero sí algo que trabajar.
        Optional<CloseRun> existente = runs.find(businessDate, phase, alcance);
        if (existente.isPresent()) {
            return Optional.of(new Planificacion(existente.get(), false));
        }

        guardarBarreraDeFase(businessDate, phase, alcance);

        LockKey key = LockKey.of("closing", "run", businessDate, phase, alcance);
        return lock.withLock(key, PLAN_LEASE, () -> {
            // Segunda comprobación con el candado tomado: entre la primera y ésta, otro pod pudo
            // haberla creado. Sin esto, el candado sólo estrecharía la ventana en vez de cerrarla.
            Optional<CloseRun> yaCreada = runs.find(businessDate, phase, alcance);
            if (yaCreada.isPresent()) {
                return new Planificacion(yaCreada.get(), false);
            }
            return new Planificacion(materializar(businessDate, phase, alcance, materializador), true);
        });
    }

    @Transactional
    protected CloseRun materializar(LocalDate businessDate, ClosePhase phase, String alcance,
                                     Function<CloseRun, List<CloseUnit>> materializador) {
        CloseRun run = runs.save(CloseRun.plan(businessDate, phase, alcance));

        List<CloseUnit> nuevas = materializador.apply(run);
        int insertadas = nuevas.isEmpty() ? 0 : units.insertBatch(nuevas);

        run.materialized(insertadas);
        runs.save(run);

        log.info("Corrida planificada fase={} fecha={} alcance={} unidades={}",
                phase, businessDate, alcance, insertadas);
        return run;
    }

    /**
     * Una fase no arranca hasta que su predecesora selló.
     *
     * <p>Es la barrera que sustituye a las horas de reloj. Hoy el orden es 23:00 → 23:30 → 23:59
     * → 01:00: si el devengo tarda más de 59 minutos, la mora corre sobre datos viejos y
     * <b>nada lo detecta</b>.
     */
    private void guardarBarreraDeFase(LocalDate businessDate, ClosePhase phase, String alcance) {
        ClosePhase requerida = phase.requires();
        if (requerida == null) return;

        boolean sellada = runs.find(businessDate, requerida, alcance)
                .map(CloseRun::isSealed)
                .orElse(false);

        if (!sellada) {
            throw new FaseNoLista(phase, requerida, businessDate);
        }
    }

    /** La fase previa no ha sellado. No es un error del sistema: es que todavía no toca. */
    public static class FaseNoLista extends IllegalStateException {
        public FaseNoLista(ClosePhase phase, ClosePhase requerida, LocalDate businessDate) {
            super("La fase " + phase + " de " + businessDate + " no puede arrancar: "
                  + requerida + " todavía no ha sellado");
        }
    }
}
