package com.fintech.collections.infrastructure.adapter.in.messaging;

import com.fintech.collections.application.service.EarlyCollectionsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class InstallmentUpcomingListener {

    private static final Logger log = LoggerFactory.getLogger(InstallmentUpcomingListener.class);

    private final EarlyCollectionsService earlyCollectionsService;

    public InstallmentUpcomingListener(EarlyCollectionsService earlyCollectionsService) {
        this.earlyCollectionsService = earlyCollectionsService;
    }

    @KafkaListener(topics = "credit-portfolio.installment-upcoming",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "installmentUpcomingListenerContainerFactory")
    public void onMessage(InstallmentUpcomingPayload payload) {
        log.debug("installment-upcoming received creditAccountId={} dueDate={}",
                payload.creditAccountId(), payload.dueDate());
        earlyCollectionsService.onInstallmentUpcoming(
                payload.creditAccountId(), payload.dueDate(), payload.totalAmount());
    }
}
