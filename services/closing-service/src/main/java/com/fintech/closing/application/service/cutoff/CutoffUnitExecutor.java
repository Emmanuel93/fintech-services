package com.fintech.closing.application.service.cutoff;

import com.fintech.closing.application.port.out.CloseUnitRepository;
import com.fintech.closing.application.service.CloseRunWorker;
import com.fintech.closing.application.service.CloseRunWorker.UnitOutcome;
import com.fintech.closing.application.service.phase.CutoffPhaseProcessor;
import com.fintech.closing.domain.CloseUnit;
import com.fintech.closing.domain.UnitStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Corta la cuenta de un mensaje de {@code closing.cutoff-due}.
 *
 * <p>Lo corre cualquier pod del grupo. Un mensaje repetido —Kafka entrega al menos una vez, y el
 * monitor reenvía lo que cree perdido— encuentra la unidad tomada o terminada y no hace nada.
 */
@Service
public class CutoffUnitExecutor {

    private static final Logger log = LoggerFactory.getLogger(CutoffUnitExecutor.class);

    private final CloseUnitRepository units;
    private final CloseRunWorker worker;
    private final CutoffPhaseProcessor cutoffProcessor;

    public CutoffUnitExecutor(CloseUnitRepository units, CloseRunWorker worker, CutoffPhaseProcessor cutoffProcessor) {
        this.units           = units;
        this.worker          = worker;
        this.cutoffProcessor = cutoffProcessor;
    }

    public UnitOutcome execute(UUID unitId, LocalDate businessDate) {
        CloseUnit unit = units.findById(unitId).orElse(null);
        if (unit == null) {
            log.warn("cutoff-due de una unidad que no existe unitId={} — ignorado", unitId);
            return UnitOutcome.TAKEN_BY_OTHER;
        }
        if (unit.status() != UnitStatus.PENDING) {
            log.debug("Unidad {} ya {} — mensaje repetido", unitId, unit.status());
            return UnitOutcome.TAKEN_BY_OTHER;
        }
        UnitOutcome outcome = worker.workUnit(unit, businessDate, cutoffProcessor);
        log.debug("Corte de la cuenta {} → {}", unit.getCreditAccountId(), outcome);
        return outcome;
    }
}
