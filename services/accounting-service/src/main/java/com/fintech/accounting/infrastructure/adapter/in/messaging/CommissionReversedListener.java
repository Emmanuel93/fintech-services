package com.fintech.accounting.infrastructure.adapter.in.messaging;

import com.fintech.accounting.application.service.CommissionPostingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class CommissionReversedListener {

    private static final Logger log = LoggerFactory.getLogger(CommissionReversedListener.class);
    private final CommissionPostingService commissionPostingService;

    public CommissionReversedListener(CommissionPostingService commissionPostingService) {
        this.commissionPostingService = commissionPostingService;
    }

    @KafkaListener(topics = "commission.commission-reversed",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "commissionReversedListenerContainerFactory")
    public void onMessage(CommissionReversedPayload p) {
        log.debug("commission-reversed commissionId={} amount={}", p.commissionId(), p.amount());
        commissionPostingService.onCommissionReversed(p.commissionId(), p.creditAccountId(), p.amount(), p.occurredOn());
    }
}
