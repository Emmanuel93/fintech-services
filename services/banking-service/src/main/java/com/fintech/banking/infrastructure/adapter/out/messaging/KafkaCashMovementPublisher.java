package com.fintech.banking.infrastructure.adapter.out.messaging;

import com.fintech.banking.application.port.out.CashMovementPublisher;
import com.fintech.banking.domain.InternalMovement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Publishes {@code banking.cash-movement-recorded} after the commit: one message per movement of the cash book. */
@Component
class KafkaCashMovementPublisher implements CashMovementPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaCashMovementPublisher.class);
    static final String TOPIC = "banking.cash-movement-recorded";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    KafkaCashMovementPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void recorded(InternalMovement m) {
        var payload = new CashMovementPayload("CASH:" + m.getId(), m.getId(), m.getMovementType(), m.getDirection(),
                m.getAmount(), m.getBusinessDate(), m.getReference(), m.getSourceEventId(), m.getPortfolioDate(),
                m.getTrackingKey());
        Runnable send = () -> kafkaTemplate.send(TOPIC, m.getId().toString(), payload).whenComplete((r, ex) -> {
            if (ex != null) log.error("No se pudo publicar el movimiento de caja {}: {}", m.getId(), ex.getMessage());
        });
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { send.run(); }
            });
        } else {
            send.run();
        }
    }

    /** {@code businessDate}: the SPEI operation date (the bank's); {@code portfolioDate}: the portfolio's. */
    record CashMovementPayload(String eventId, UUID movementId, String movementType, String direction,
                               BigDecimal amount, LocalDate businessDate, String reference, String sourceEventId,
                               LocalDate portfolioDate, String trackingKey) {}
}
