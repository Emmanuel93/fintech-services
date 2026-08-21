package com.fintech.accounting.infrastructure.adapter.in.messaging;

import com.fintech.accounting.application.service.CommissionPostingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class CommissionLiquidatedListener {

    private static final Logger log = LoggerFactory.getLogger(CommissionLiquidatedListener.class);
    private final CommissionPostingService commissionPostingService;

    public CommissionLiquidatedListener(CommissionPostingService commissionPostingService) {
        this.commissionPostingService = commissionPostingService;
    }

    @KafkaListener(topics = "commission.commission-liquidated",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "commissionLiquidatedListenerContainerFactory")
    public void onMessage(CommissionLiquidatedPayload p) {
        log.debug("commission-liquidated batchId={} total={}", p.batchId(), p.totalAmount());
        commissionPostingService.onCommissionLiquidated(p.batchId(), p.totalAmount(), p.occurredOn());
    }
}
