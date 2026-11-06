package com.fintech.payments.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fintech.payments.application.service.CustomerBalanceService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * An installment is due ({@code credit-portfolio.installment-due}, published by the day close with the day that
 * starts): the payer's balance in favor, if any, pays it that same day.
 */
@Component
public class InstallmentDueListener {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InstallmentDuePayload(UUID installmentId, UUID creditAccountId, int installmentNumber,
                                        LocalDate dueDate, BigDecimal totalAmount) {}

    private final CustomerBalanceService balances;

    public InstallmentDueListener(CustomerBalanceService balances) {
        this.balances = balances;
    }

    @KafkaListener(topics = "credit-portfolio.installment-due", groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "installmentDueListenerContainerFactory")
    public void onInstallmentDue(InstallmentDuePayload due) {
        if (due.creditAccountId() == null || due.installmentId() == null) {
            return;
        }
        balances.applyToDueInstallment(due.creditAccountId(), due.installmentId(), due.totalAmount());
    }
}
