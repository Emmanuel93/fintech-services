package com.fintech.closing.application.service.cutoff;

import com.fintech.closing.application.ClosingProperties;
import com.fintech.closing.application.port.out.CloseRunRepository;
import com.fintech.closing.application.port.out.CutoffUnitStore;
import com.fintech.closing.application.service.LeaseReaper;
import com.fintech.closing.domain.ClosePhase;
import com.fintech.closing.domain.CloseRun;
import com.fintech.closing.domain.RunStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Lo que mantiene vivo un corte repartido: recupera, reintenta, reenvía y cierra.
 *
 * <p>Cada vuelta, por cada corrida de corte abierta:
 * <ol>
 *   <li>devuelve a la cola lo que un pod muerto dejó tomado ({@link LeaseReaper});</li>
 *   <li>devuelve las fallidas que aún tienen intentos;</li>
 *   <li>reenvía lo PENDING que nunca salió o cuyo mensaje se perdió;</li>
 *   <li>y si ya no queda nada en vuelo, la cierra.</li>
 * </ol>
 *
 * <p>Corre en <b>una</b> réplica (ShedLock). Las cuentas las trabajan todas.
 */
@Service
public class CutoffRunMonitor {

    private static final Logger log = LoggerFactory.getLogger(CutoffRunMonitor.class);

    private static final int REAP_LIMIT = 1_000;

    private final CloseRunRepository runs;
    private final CutoffUnitStore store;
    private final LeaseReaper reaper;
    private final CutoffDispatcher dispatcher;
    private final CutoffRunCompletion completion;
    private final ClosingProperties properties;
    private final com.fintech.closing.application.port.out.PortfolioDayCloseLog dayCloses;

    public CutoffRunMonitor(CloseRunRepository runs, CutoffUnitStore store, LeaseReaper reaper,
                            CutoffDispatcher dispatcher, CutoffRunCompletion completion,
                            ClosingProperties properties,
                            com.fintech.closing.application.port.out.PortfolioDayCloseLog dayCloses) {
        this.runs       = runs;
        this.store      = store;
        this.reaper     = reaper;
        this.dispatcher = dispatcher;
        this.completion = completion;
        this.properties = properties;
        this.dayCloses  = dayCloses;
    }

    /** @return cuántas corridas cerró esta vuelta */
    public int tick() {
        int recuperadas = reaper.reap(REAP_LIMIT);
        if (recuperadas > 0) {
            log.warn("{} unidades devueltas a la cola: su pod dejó vencer el arrendamiento", recuperadas);
        }

        int cerradas = 0;
        for (UUID runId : store.openRuns(ClosePhase.CUTOFF)) {
            CloseRun run = runs.findById(runId).orElse(null);
            if (run == null) continue;

            if (run.status() == RunStatus.PLANNED) {
                resumeMaterialization(run);
            }

            int reintento = store.requeueFailed(runId, properties.getCutoff().getMaxAttempts());
            if (reintento > 0) {
                log.info("Corte del {}: {} cuentas fallidas vuelven a intentarse", run.getBusinessDate(), reintento);
            }
            dispatcher.dispatch(runId, run.getBusinessDate());
            if (completion.tryComplete(run)) cerradas++;
        }
        return cerradas;
    }

    /**
     * Una corrida que quedó PLANNED: el pod que la materializaba murió entre insertar las unidades y
     * marcarla en curso. Se rematerializa —el {@code INSERT} es idempotente— y se pone a trabajar.
     */
    private void resumeMaterialization(CloseRun run) {
        store.materialize(run.getRunId(), run.getBusinessDate(), properties.getCutoff().getDistributorProductTypes(),
                dayCloses.heldAccounts(run.getBusinessDate()));
        int total = store.counts(run.getRunId()).total();
        run.materialized(total);
        runs.save(run);
        store.saveBreakdown(run.getRunId(), store.breakdown(run.getRunId()));
        log.warn("Corte del {} retomado tras quedar a medio planificar: {} cuentas", run.getBusinessDate(), total);
    }
}
