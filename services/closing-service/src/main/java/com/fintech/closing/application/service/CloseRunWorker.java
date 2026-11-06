package com.fintech.closing.application.service;

import com.fintech.closing.application.ClosingProperties;
import com.fintech.closing.application.port.in.CloseUnitProcessor;
import com.fintech.closing.application.port.out.CloseUnitRepository;
import com.fintech.closing.domain.CloseUnit;
import com.fintech.shared.lock.DistributedLock;
import com.fintech.shared.lock.LockHandle;
import com.fintech.shared.lock.LockKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Toma unidades y las procesa. Es el corazón del reparto entre pods.
 *
 * <p><b>Sin candados de base de datos.</b> La secuencia es: leer candidatas con un {@code SELECT}
 * normal, ganar el candado de Redis de esa unidad, y confirmar con un <b>CAS optimista</b>
 * ({@code UPDATE … WHERE status = 'PENDING'}). Si el CAS devuelve cero filas, otro pod se adelantó
 * y se sigue con la siguiente — no se espera a nadie.
 *
 * <p><b>Cada unidad en su propia transacción corta.</b> Una que falla queda {@code FAILED} con su
 * causa y la corrida sigue viva. El as-is ya hace algo parecido con un {@code try/catch}, pero
 * pierde el registro: aquí queda una fila reintentable.
 */
@Service
public class CloseRunWorker {

    private static final Logger log = LoggerFactory.getLogger(CloseRunWorker.class);

    private final CloseUnitRepository units;
    private final DistributedLock lock;
    private final ClosingProperties properties;
    private final TransactionTemplate tx;

    public CloseRunWorker(CloseUnitRepository units, DistributedLock lock,
                           ClosingProperties properties, TransactionTemplate tx) {
        this.units      = units;
        this.lock       = lock;
        this.properties = properties;
        this.tx         = tx;
    }

    public record Resultado(int tomadas, int hechas, int fallidas, int omitidas, int colisiones) {
        public boolean didWork() { return tomadas > 0; }
    }

    /**
     * Trabaja un lote. Devuelve qué pasó, para que el llamador sepa si queda algo.
     *
     * @param offset desplazamiento de este pod dentro de la página de candidatas. Sin él, todas
     *               las réplicas competirían siempre por las mismas filas y el candado rechazaría
     *               casi todos los intentos: se gastaría más en coordinar que en trabajar.
     */
    public Resultado workBatch(UUID runId, LocalDate businessDate,
                                CloseUnitProcessor processor, int offset) {
        List<CloseUnit> candidatas = units.findPending(runId, offset, properties.getBatchSize());
        if (candidatas.isEmpty()) {
            return new Resultado(0, 0, 0, 0, 0);
        }

        int tomadas = 0, hechas = 0, fallidas = 0, omitidas = 0, colisiones = 0;

        for (CloseUnit candidata : candidatas) {
            switch (workUnit(candidata, businessDate, processor)) {
                case TAKEN_BY_OTHER -> colisiones++;
                case DONE    -> { tomadas++; hechas++; }
                case SKIPPED -> { tomadas++; omitidas++; }
                case FAILED  -> { tomadas++; fallidas++; }
            }
        }

        log.debug("Lote trabajado runId={} tomadas={} hechas={} fallidas={} omitidas={} colisiones={}",
                runId, tomadas, hechas, fallidas, omitidas, colisiones);
        return new Resultado(tomadas, hechas, fallidas, omitidas, colisiones);
    }

    /** Lo que pasó con una unidad que se intentó trabajar. */
    public enum UnitOutcome { DONE, FAILED, SKIPPED, TAKEN_BY_OTHER }

    /**
     * Una unidad: ganar su candado, tomarla con el CAS y procesarla.
     *
     * <p>Es la misma secuencia la llame el lote ({@link #workBatch}) o el consumidor del tópico de
     * corte: el mensaje dice qué unidad toca, pero quien decide si de verdad le toca a este pod es el
     * CAS. Kafka entrega al menos una vez y un rebalanceo puede repartir el mismo mensaje dos veces;
     * el segundo encuentra la unidad ya tomada y sigue de largo.
     */
    public UnitOutcome workUnit(CloseUnit candidata, LocalDate businessDate, CloseUnitProcessor processor) {
        LockKey key = LockKey.of("closing", "unit", candidata.getRunId(), candidata.getUnitKey());
        Optional<LockHandle> handle = lock.tryAcquire(key, properties.getLease());
        if (handle.isEmpty()) {
            return UnitOutcome.TAKEN_BY_OTHER;  // otro pod la tiene: no se espera
        }

        LockHandle h = handle.get();
        try {
            // CAS optimista. Cero filas = otro pod ganó la carrera entre el SELECT y aquí.
            Instant expira = Instant.now().plus(properties.getLease());
            int tomada = units.claim(candidata.getUnitId(), properties.getNodeId(), h.fencingToken(), expira);
            if (tomada == 0) {
                return UnitOutcome.TAKEN_BY_OTHER;
            }
            return processUnit(candidata.getUnitId(), businessDate, processor, h.fencingToken());
        } finally {
            lock.release(h);
        }
    }

    /**
     * Una unidad, una transacción propia.
     *
     * <p><b>Con {@link TransactionTemplate} y no con {@code @Transactional}</b>, por la misma razón
     * que ya documenta {@code OutboxRelayService} en este monorepo: una anotación sobre un método
     * llamado desde dentro de la misma clase <b>no pasa por el proxy y no hace nada</b>. Sería un
     * {@code REQUIRES_NEW} decorativo, y el primer fallo de una unidad marcaría la transacción del
     * lote entero como rollback-only, revirtiendo el trabajo ya hecho de las anteriores.
     *
     * <p>Son dos transacciones y no una a propósito: la primera hace el trabajo y se deshace si
     * falla; la segunda graba la marca de {@code FAILED}. Si la marca fuera en la misma, el
     * rollback se la llevaría y la unidad volvería a aparecer como {@code CLAIMED} sin causa.
     */
    private UnitOutcome processUnit(UUID unitId, LocalDate businessDate,
                                      CloseUnitProcessor processor, long fencingToken) {
        var causa = new java.util.concurrent.atomic.AtomicReference<String>();

        UnitOutcome desenlace = tx.execute(status -> {
            CloseUnit unit = units.findById(unitId).orElseThrow();
            try {
                processor.process(unit, businessDate);
                unit.markDone(fencingToken);
                units.save(unit);
                return UnitOutcome.DONE;
            } catch (UnitSkipped skip) {
                unit.markSkipped(skip.getMessage());
                units.save(unit);
                return UnitOutcome.SKIPPED;
            } catch (Exception ex) {
                causa.set(ex.getClass().getSimpleName() + ": " + ex.getMessage());
                status.setRollbackOnly();
                return UnitOutcome.FAILED;
            }
        });

        if (desenlace == UnitOutcome.FAILED) {
            tx.executeWithoutResult(status -> {
                CloseUnit unit = units.findById(unitId).orElseThrow();
                unit.markFailed(fencingToken, causa.get());
                units.save(unit);
                log.warn("Unidad fallida runId={} unitKey={}: {}",
                        unit.getRunId(), unit.getUnitKey(), causa.get());
            });
        }
        return desenlace;
    }

    /** La unidad no aplica. No es un fallo y no debe bloquear el sello. */
    public static class UnitSkipped extends RuntimeException {
        public UnitSkipped(String reason) { super(reason); }
    }
}
