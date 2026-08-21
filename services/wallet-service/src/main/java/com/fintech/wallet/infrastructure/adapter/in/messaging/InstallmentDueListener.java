package com.fintech.wallet.infrastructure.adapter.in.messaging;

import com.fintech.wallet.application.service.WalletProjectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class InstallmentDueListener {

    private static final Logger log = LoggerFactory.getLogger(InstallmentDueListener.class);

    private final WalletProjectionService projectionService;

    public InstallmentDueListener(WalletProjectionService projectionService) {
        this.projectionService = projectionService;
    }

    @KafkaListener(
            topics = "credit-portfolio.installment-due",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "installmentDueListenerContainerFactory")
    public void onInstallmentDue(InstallmentDuePayload event) {
        log.info("installment-due received creditAccountId={} installment={} dueDate={}",
                event.creditAccountId(), event.installmentNumber(), event.dueDate());

        projectionService.onInstallmentDue(
                event.creditAccountId(),
                event.totalAmount(),
                event.dueDate());
    }
}
