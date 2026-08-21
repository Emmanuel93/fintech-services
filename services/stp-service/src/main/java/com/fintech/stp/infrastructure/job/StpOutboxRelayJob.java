package com.fintech.stp.infrastructure.job;

import com.fintech.stp.application.port.in.RelayOutboxUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Vacía el outbox contra STP.
 *
 * <p>Sin ShedLock: el {@code FOR UPDATE SKIP LOCKED} del repositorio ya garantiza que dos réplicas
 * tomen lotes distintos. Añadir una dependencia de coordinación para resolver lo mismo dos veces
 * sería ruido.
 */
@Component
public class StpOutboxRelayJob {

    private static final Logger log = LoggerFactory.getLogger(StpOutboxRelayJob.class);

    private final RelayOutboxUseCase relayOutbox;

    public StpOutboxRelayJob(RelayOutboxUseCase relayOutbox) {
        this.relayOutbox = relayOutbox;
    }

    // ISO-8601 y no "5s": @Scheduled es de Spring Framework core y no usa el DurationStyle
    // relajado de Boot — un "5s" aquí revienta el arranque con NumberFormatException.
    @Scheduled(fixedDelayString = "${fintech.stp.outbox.relay-interval:PT5S}")
    public void relay() {
        try {
            int processed = relayOutbox.relayPending();
            if (processed > 0) {
                log.debug("Outbox relay processed {} messages", processed);
            }
        } catch (RuntimeException e) {
            // Un fallo del relay no puede tumbar el scheduler: la siguiente corrida lo reintenta.
            log.error("Outbox relay run failed: {}", e.getMessage(), e);
        }
    }
}
