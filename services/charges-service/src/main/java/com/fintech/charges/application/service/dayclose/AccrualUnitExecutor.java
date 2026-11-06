package com.fintech.charges.application.service.dayclose;

import com.fintech.charges.application.port.out.AccrualUnitStore;
import com.fintech.charges.application.service.dayclose.AccrualUnitWorker.StaleUnitException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Devenga la cuenta de un mensaje de {@code charges.accrual-due}. Lo corre cualquier pod del grupo.
 *
 * <p>Toma la unidad con CAS; un mensaje repetido la encuentra tomada o terminada y no hace nada. Si el
 * devengo falla, la unidad queda {@code FAILED} con el error —en otra transacción, para que el rollback
 * no se lleve la marca— y el monitor la reintenta o la pone en cuarentena.
 */
@Service
public class AccrualUnitExecutor {

    private static final Logger log = LoggerFactory.getLogger(AccrualUnitExecutor.class);
    private static final int MAX_ERROR = 1_000;

    private final AccrualUnitStore units;
    private final AccrualUnitWorker worker;
    private final UnitSettings settings;

    public AccrualUnitExecutor(AccrualUnitStore units, AccrualUnitWorker worker, UnitSettings settings) {
        this.units    = units;
        this.worker   = worker;
        this.settings = settings;
    }

    public void execute(LocalDate businessDate, UUID creditAccountId) {
        var tomada = units.claim(businessDate, creditAccountId, settings.lease());
        if (tomada.isEmpty()) {
            log.debug("Cuenta {} del {} ya tomada o terminada — mensaje repetido", creditAccountId, businessDate);
            return;
        }
        try {
            worker.accrue(businessDate, creditAccountId, tomada.get());
        } catch (StaleUnitException e) {
            log.info("{} — su devengo se deshizo", e.getMessage());
        } catch (RuntimeException e) {
            // Si la unidad ya quedó DONE (el envío falló después del commit), esto no la toca: sus cargos
            // los reenvía el seguimiento del cierre con su mismo id.
            units.fail(businessDate, creditAccountId, tomada.get().fencingToken(), rootCause(e));
            log.warn("La cuenta {} no devengó el {}: {}", creditAccountId, businessDate, rootCause(e));
        }
    }

    /** El mensaje de la causa raíz: es el que dice qué dato está mal (un check, un nulo). */
    static String rootCause(Throwable e) {
        Throwable raiz = e;
        while (raiz.getCause() != null && raiz.getCause() != raiz) raiz = raiz.getCause();
        String msg = raiz.getClass().getSimpleName() + ": " + raiz.getMessage();
        return msg.length() > MAX_ERROR ? msg.substring(0, MAX_ERROR) : msg;
    }
}
