package com.fintech.disbursement.infrastructure.job;

import com.fintech.disbursement.application.DisbursementProperties;
import com.fintech.disbursement.application.port.in.DispatchDisbursementsUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Vacía la cola de órdenes despachables.
 *
 * <p>Sin coordinador externo: el {@code FOR UPDATE SKIP LOCKED} del repositorio ya garantiza que
 * dos réplicas tomen lotes distintos. El legado resolvía lo mismo con dos crons y cuatro réplicas
 * que se pisaban.
 */
@Component
public class DisbursementDispatchJob {

    private static final Logger log = LoggerFactory.getLogger(DisbursementDispatchJob.class);

    private final DispatchDisbursementsUseCase dispatchDisbursements;
    private final DisbursementProperties properties;

    public DisbursementDispatchJob(DispatchDisbursementsUseCase dispatchDisbursements,
                                   DisbursementProperties properties) {
        this.dispatchDisbursements = dispatchDisbursements;
        this.properties = properties;
    }

    // ISO-8601 y no "5s": @Scheduled es de Spring Framework core y no usa el DurationStyle
    // relajado de Boot — un "5s" aquí revienta el arranque con NumberFormatException.
    @Scheduled(fixedDelayString = "${fintech.disbursement.dispatch.interval:PT5S}")
    public void dispatch() {
        if (!properties.getDispatch().isEnabled()) {
            return;
        }
        try {
            int dispatched = dispatchDisbursements.dispatchDue();
            if (dispatched > 0) {
                log.debug("Despachadas {} órdenes de desembolso", dispatched);
            }
        } catch (RuntimeException e) {
            // Un fallo de la corrida no puede tumbar el scheduler: la siguiente lo reintenta.
            log.error("La corrida de despacho falló: {}", e.getMessage(), e);
        }
    }
}
