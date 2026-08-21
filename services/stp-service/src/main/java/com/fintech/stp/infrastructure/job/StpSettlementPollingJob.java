package com.fintech.stp.infrastructure.job;

import com.fintech.stp.application.port.in.PollSettlementsUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Consulta a STP el desenlace de las órdenes en vuelo.
 *
 * <p>Esto es lo que sustituye a los webhooks: la plataforma no expone nada a internet, así que la
 * información llega porque la vamos a buscar. Sólo corre si hay órdenes en vuelo — el servicio de
 * aplicación sale temprano si no las hay, así que no hay costo cuando no pasa nada.
 */
@Component
@ConditionalOnProperty(name = "fintech.stp.polling.enabled", havingValue = "true", matchIfMissing = true)
public class StpSettlementPollingJob {

    private static final Logger log = LoggerFactory.getLogger(StpSettlementPollingJob.class);

    private final PollSettlementsUseCase pollSettlements;

    public StpSettlementPollingJob(PollSettlementsUseCase pollSettlements) {
        this.pollSettlements = pollSettlements;
    }

    // ISO-8601 obligatorio: ver StpOutboxRelayJob.
    @Scheduled(fixedDelayString = "${fintech.stp.polling.interval:PT3M}")
    public void poll() {
        try {
            int applied = pollSettlements.pollInFlightOrders();
            if (applied > 0) {
                log.info("Settlement poll applied {} new observations", applied);
            }
        } catch (RuntimeException e) {
            log.error("Settlement poll run failed: {}", e.getMessage(), e);
        }
    }
}
