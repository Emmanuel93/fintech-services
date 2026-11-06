package com.fintech.closing.application.service.cutoff;

import com.fintech.closing.application.ClosingProperties;
import com.fintech.closing.application.port.out.CloseRunRepository;
import com.fintech.closing.application.port.out.CutoffUnitStore;
import com.fintech.closing.application.port.out.PortfolioDayCloseLog;
import com.fintech.closing.domain.ClosePhase;
import com.fintech.closing.domain.CloseRun;
import com.fintech.shared.kafka.ConsumerCatchUp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Suelta el corte de una cuenta retenida: cargos la dejó fuera del cierre de su día, después devengó, y
 * cartera ya cuadró su interés ({@code credit-portfolio.account-day-closed}).
 *
 * <p>Con la misma compuerta que el corte del día: closing tiene que haber consumido lo que cartera publicó
 * antes del aviso —los saldos de esa cuenta con su interés—. Entonces la unidad pasa a pendiente, la
 * corrida se reabre si ya había terminado, y la cuenta se corta como cualquier otra, con su saldo al cierre
 * de su día. Si el aviso llega antes de que el corte se planifique, queda anotado y la cuenta nace pendiente.
 */
@Service
public class CutoffHeldRelease {

    private static final Logger log = LoggerFactory.getLogger(CutoffHeldRelease.class);

    private final CloseRunRepository runs;
    private final CutoffUnitStore store;
    private final PortfolioDayCloseLog dayCloses;
    private final CutoffDispatcher dispatcher;
    private final ConsumerCatchUp catchUp;
    private final ClosingProperties properties;
    private final CutoffSnapshotAttacher snapshots;
    private final String consumerGroup;

    public CutoffHeldRelease(CloseRunRepository runs, CutoffUnitStore store, PortfolioDayCloseLog dayCloses,
                             CutoffDispatcher dispatcher, ConsumerCatchUp catchUp, ClosingProperties properties,
                             CutoffSnapshotAttacher snapshots,
                             @Value("${spring.kafka.consumer.group-id:closing-service}") String consumerGroup) {
        this.snapshots     = snapshots;
        this.runs          = runs;
        this.store         = store;
        this.dayCloses     = dayCloses;
        this.dispatcher    = dispatcher;
        this.catchUp       = catchUp;
        this.properties    = properties;
        this.consumerGroup = consumerGroup;
    }

    /**
     * @return si había un corte retenido de esa cuenta y quedó en la cola
     * @throws IllegalStateException si closing no ha consumido los saldos de cartera; el aviso se reintenta
     */
    public boolean release(LocalDate businessDate, UUID creditAccountId) {
        dayCloses.recordRelease(businessDate, creditAccountId);
        CloseRun run = runs.find(businessDate, ClosePhase.CUTOFF, CutoffRunService.SCOPE).orElse(null);
        if (run == null) return false;

        var consumo = catchUp.await(consumerGroup, CutoffRunService.PORTFOLIO_TOPICS,
                properties.getCutoff().getGateWait(), properties.getCutoff().getGatePoll());
        if (!consumo.caughtUp()) {
            throw new IllegalStateException("La cuenta " + creditAccountId + " del " + businessDate + " no se suelta"
                    + " todavía: faltan " + consumo.pending() + " movimientos de cartera por consumir");
        }
        if (!store.release(run.getRunId(), creditAccountId)) return false;

        if (!run.isRunning()) {
            run.resumeHeld();
            runs.save(run);
        }
        // The held unit was planned with the rest, so it normally has its snapshot already; this covers one that did not.
        snapshots.attach(run.getRunId(), businessDate);
        dispatcher.dispatch(run.getRunId(), businessDate);
        log.info("Corte del {} de la cuenta {} soltado: su interés ya llegó", businessDate, creditAccountId);
        return true;
    }
}
