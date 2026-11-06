package com.fintech.accounting.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fintech.accounting.application.service.CustomerBalancePostingService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** A payer's balance in favor changed ({@code payments.customer-balance-moved}). */
@Component
public class CustomerBalanceMovedListener {

    /** {@code amount} is signed: positive raises the balance, negative lowers it. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CustomerBalanceMovedPayload(UUID movementId, UUID partyId, String movementType, BigDecimal amount,
                                              UUID inboundCreditId, UUID paymentOrderId, UUID creditAccountId,
                                              LocalDate businessDate) {}

    private final CustomerBalancePostingService balances;

    public CustomerBalanceMovedListener(CustomerBalancePostingService balances) {
        this.balances = balances;
    }

    @KafkaListener(topics = "payments.customer-balance-moved", groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "customerBalanceMovedListenerContainerFactory")
    public void onMessage(CustomerBalanceMovedPayload p) {
        if (p.movementId() == null || p.partyId() == null || p.amount() == null || p.businessDate() == null) {
            return;
        }
        balances.onMovement(p.movementId(), p.partyId(), p.movementType(), p.amount(), p.inboundCreditId(),
                p.paymentOrderId(), p.creditAccountId(), p.businessDate());
    }
}
