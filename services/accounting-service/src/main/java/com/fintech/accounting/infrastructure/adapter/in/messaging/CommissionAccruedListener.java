package com.fintech.accounting.infrastructure.adapter.in.messaging;

import com.fintech.accounting.application.service.CommissionPostingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class CommissionAccruedListener {

    private static final Logger log = LoggerFactory.getLogger(CommissionAccruedListener.class);
    private final CommissionPostingService commissionPostingService;

    public CommissionAccruedListener(CommissionPostingService commissionPostingService) {
        this.commissionPostingService = commissionPostingService;
    }

    @KafkaListener(topics = "commission.commission-accrued",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "commissionAccruedListenerContainerFactory")
    public void onMessage(CommissionAccruedPayload p) {
        log.debug("commission-accrued commissionId={} amount={}", p.commissionId(), p.amount());
        commissionPostingService.onCommissionAccrued(p.commissionId(), p.creditAccountId(), p.amount(), p.occurredOn());
    }
}
