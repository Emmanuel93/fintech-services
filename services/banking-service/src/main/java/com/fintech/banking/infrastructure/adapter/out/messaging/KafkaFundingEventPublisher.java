package com.fintech.banking.infrastructure.adapter.out.messaging;

import com.fintech.banking.application.port.out.FundingEventPublisher;
import com.fintech.banking.domain.FundingMovement;
import com.fintech.banking.domain.FundingSource;
import com.fintech.shared.time.BusinessClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Publishes {@code banking.funding-movement-recorded} after the commit: an event of a movement that rolled back would
 * book money that never moved. Keyed by the source, so its movements arrive in order.
 */
@Component
class KafkaFundingEventPublisher implements FundingEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaFundingEventPublisher.class);
    static final String TOPIC = "banking.funding-movement-recorded";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    KafkaFundingEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void movementRecorded(FundingSource s, FundingMovement m) {
        var payload = new FundingMovementPayload("FUNDING:" + m.getId(), m.getId(), s.getId(), s.getKind().name(),
                s.getCounterpartyType(), s.getCounterpartyName(), s.getContractReference(), m.getType().name(),
                m.getAmount(), m.getValueDate(), m.getAccruedFrom(), m.getAccruedThrough(), m.getContributedAfter(),
                m.getPrincipalAfter(), m.getInterestPayableAfter(), s.getStatus(), s.termMonths(), occurredOn(m));
        Runnable send = () -> kafkaTemplate.send(TOPIC, s.getId().toString(), payload).whenComplete((r, ex) -> {
            if (ex != null) {
                log.error("No se pudo publicar el movimiento de fondeo {} fuente={}: {}", m.getId(), s.getId(), ex.getMessage());
            }
        });
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { send.run(); }
            });
        } else {
            send.run();
        }
    }

    /** The fact's moment, in its value date: an accrual at the end of its last day, any other at midday. */
    private static Instant occurredOn(FundingMovement m) {
        LocalDate day = m.getValueDate();
        LocalTime time = m.getType() == FundingMovement.Type.INTEREST_ACCRUAL ? LocalTime.of(23, 59, 59) : LocalTime.NOON;
        return day.atTime(time).atZone(BusinessClock.current().zone()).toInstant();
    }

    record FundingMovementPayload(String eventId, UUID fundingMovementId, UUID fundingSourceId, String sourceKind,
                                  String counterpartyType, String counterpartyName, String contractReference,
                                  String movementType, BigDecimal amount, LocalDate valueDate, LocalDate accruedFrom,
                                  LocalDate accruedThrough, BigDecimal contributedAfter, BigDecimal principalAfter,
                                  BigDecimal interestPayableAfter, String sourceStatus, Integer termMonths,
                                  Instant occurredOn) {}
}
