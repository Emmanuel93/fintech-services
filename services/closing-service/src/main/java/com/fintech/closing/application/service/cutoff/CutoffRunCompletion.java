package com.fintech.closing.application.service.cutoff;

import com.fintech.closing.application.port.out.CloseRunRepository;
import com.fintech.closing.application.port.out.ClosingEventPublisher;
import com.fintech.closing.application.port.out.CutoffUnitStore;
import com.fintech.closing.application.port.out.PortfolioDayCloseLog;
import com.fintech.closing.domain.CloseRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Cierra una corrida de corte cuando ya no le queda nada en vuelo.
 *
 * <p>Sella si todas las cuentas cortaron o se omitieron con causa. Si alguna falló sin remedio queda
 * {@code FAILED}, con el detalle de cada cuenta consultable: un corte a medias que se viera sellado
 * es exactamente el número que parece bueno y no lo es.
 */
@Service
public class CutoffRunCompletion {

    private static final Logger log = LoggerFactory.getLogger(CutoffRunCompletion.class);

    private final CloseRunRepository runs;
    private final CutoffUnitStore store;
    private final ClosingEventPublisher publisher;
    private final PortfolioDayCloseLog dayCloses;

    public CutoffRunCompletion(CloseRunRepository runs, CutoffUnitStore store, ClosingEventPublisher publisher,
                               PortfolioDayCloseLog dayCloses) {
        this.runs      = runs;
        this.store     = store;
        this.publisher = publisher;
        this.dayCloses = dayCloses;
    }

    /** @return si la corrida quedó cerrada en esta llamada */
    public boolean tryComplete(CloseRun run) {
        if (!run.isRunning()) return false;

        CutoffUnitStore.StatusCounts counts = store.counts(run.getRunId());
        if (counts.open() > 0) {
            run.progress(counts.done(), counts.failed(), counts.skipped());
            runs.save(run);
            return false;
        }

        run.complete(counts.done(), counts.failed(), counts.skipped());
        runs.save(run);
        announce(run, counts);

        if (run.isSealed()) {
            log.info("Corte del {} sellado: {} cuentas cortadas, {} omitidas runId={}",
                    run.getBusinessDate(), counts.done(), counts.skipped(), run.getRunId());
        } else {
            log.warn("Corte del {} terminó con {} cuentas fallidas de {} runId={}",
                    run.getBusinessDate(), counts.failed(), counts.total(), run.getRunId());
        }
        return true;
    }

    /**
     * Avisa que el corte terminó. Es la compuerta del devengo del día siguiente en cargos, así que si
     * no sale queda anotado como pendiente y la reanudación lo vuelve a mandar.
     */
    public void announce(CloseRun run, CutoffUnitStore.StatusCounts counts) {
        try {
            publisher.publishCutoffRunCompleted(run, counts);
            dayCloses.completionAnnounced(run.getBusinessDate());
        } catch (RuntimeException e) {
            log.error("No salió el aviso de fin del corte del {}: {} — lo reintenta la reanudación",
                    run.getBusinessDate(), e.getMessage());
        }
    }
}
