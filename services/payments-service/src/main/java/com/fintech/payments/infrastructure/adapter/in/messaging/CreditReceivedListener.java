package com.fintech.payments.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fintech.payments.application.port.in.ApplyReceivedCreditUseCase;
import com.fintech.payments.application.port.in.ApplyReceivedCreditUseCase.ReceivedCreditCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A credit received on a payer's collection CLABE ({@code stp.credit-received}). Only confirmed credits are applied: a
 * returned one went back to its payer and never reached a loan.
 */
@Component
public class CreditReceivedListener {

    private static final Logger log = LoggerFactory.getLogger(CreditReceivedListener.class);

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CreditReceivedPayload(String eventId, UUID inboundCreditId, String trackingKey,
                                        LocalDate operationDate, Instant receivedAt, BigDecimal amount, UUID partyId,
                                        Long numericReference, String decision) {}

    private final ApplyReceivedCreditUseCase applyCredit;

    public CreditReceivedListener(ApplyReceivedCreditUseCase applyCredit) {
        this.applyCredit = applyCredit;
    }

    @KafkaListener(topics = "stp.credit-received", groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "creditReceivedListenerContainerFactory")
    public void onCreditReceived(CreditReceivedPayload credit) {
        if (!"CONFIRMED".equals(credit.decision()) || credit.partyId() == null) {
            log.debug("Abono {} no se aplica: decisión {}", credit.trackingKey(), credit.decision());
            return;
        }
        applyCredit.apply(new ReceivedCreditCommand(credit.inboundCreditId(), credit.partyId(), credit.amount(),
                credit.receivedAt(), credit.operationDate(), credit.trackingKey(), credit.numericReference()));
    }
}
