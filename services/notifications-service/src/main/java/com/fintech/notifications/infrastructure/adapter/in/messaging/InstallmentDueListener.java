package com.fintech.notifications.infrastructure.adapter.in.messaging;

import com.fintech.notifications.application.service.NotificationTriggerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class InstallmentDueListener {

    private static final Logger log = LoggerFactory.getLogger(InstallmentDueListener.class);
    private final NotificationTriggerService triggerService;

    public InstallmentDueListener(NotificationTriggerService triggerService) {
        this.triggerService = triggerService;
    }

    @KafkaListener(topics = "credit-portfolio.installment-due",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "installmentDueListenerContainerFactory")
    public void onMessage(InstallmentDuePayload p) {
        log.debug("installment-due creditAccountId={} installment={} dueDate={}",
                p.creditAccountId(), p.installmentNumber(), p.dueDate());
        // El id de la mensualidad es estable: si el job se corre dos veces, el
        // cliente no recibe el aviso duplicado.
        String sourceEventId = "installment-due:" + p.installmentId();
        triggerService.onInstallmentDue(sourceEventId, p.creditAccountId(),
                p.installmentNumber(), p.dueDate(), p.totalAmount());
    }
}
