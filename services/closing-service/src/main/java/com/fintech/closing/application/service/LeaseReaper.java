package com.fintech.closing.application.service;

import com.fintech.closing.application.port.out.CloseUnitRepository;
import com.fintech.closing.domain.CloseUnit;
import com.fintech.shared.lock.DistributedLock;
import com.fintech.shared.lock.LockKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Devuelve a la cola las unidades que un pod muerto se llevó.
 *
 * <p>Es lo que el as-is no tiene: hoy, si el proceso muere a media corrida, la mitad de las
 * cuentas queda sin procesar y <b>nadie se entera</b> — el job termina, el log dice
 * {@code success=N} y el hueco aparece semanas después cuadrando mal el mes.
 *
 * <p><b>Dos condiciones, no una.</b> Se exige que el arrendamiento haya vencido en la base
 * <em>y</em> que la llave ya no exista en Redis. El TTL de Redis es la verdad —lo suelta solo—;
 * la columna es lo consultable. Mirar sólo la columna liberaría unidades que un pod vivo sigue
 * trabajando con el candado renovado.
 */
@Service
public class LeaseReaper {

    private static final Logger log = LoggerFactory.getLogger(LeaseReaper.class);

    private final CloseUnitRepository units;
    private final DistributedLock lock;

    public LeaseReaper(CloseUnitRepository units, DistributedLock lock) {
        this.units = units;
        this.lock  = lock;
    }

    @Transactional
    public int reap(int limit) {
        List<CloseUnit> vencidas = units.findExpiredLeases(Instant.now(), limit);
        int devueltas = 0;

        for (CloseUnit unit : vencidas) {
            LockKey key = LockKey.of("closing", "unit", unit.getRunId(), unit.getUnitKey());

            // Si se puede tomar el candado, es que nadie lo tiene: el pod que la reclamó murió.
            // Se toma y se suelta enseguida — sólo interesa la respuesta, no el candado.
            var handle = lock.tryAcquire(key, Duration.ofSeconds(5));
            if (handle.isEmpty()) {
                log.debug("Unidad {} con arrendamiento vencido pero candado vivo — se respeta",
                        unit.getUnitKey());
                continue;
            }
            try {
                unit.release();
                units.save(unit);
                devueltas++;
                log.info("Unidad {} devuelta a la cola: el arrendamiento de {} venció",
                        unit.getUnitKey(), unit.getLeaseOwner());
            } finally {
                lock.release(handle.get());
            }
        }
        return devueltas;
    }
}
