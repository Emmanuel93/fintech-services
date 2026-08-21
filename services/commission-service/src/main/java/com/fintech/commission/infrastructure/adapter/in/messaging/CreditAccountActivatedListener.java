package com.fintech.commission.infrastructure.adapter.in.messaging;

import com.fintech.commission.application.service.PromoterAssignmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class CreditAccountActivatedListener {

    private static final Logger log = LoggerFactory.getLogger(CreditAccountActivatedListener.class);
    private final PromoterAssignmentService assignmentService;

    public CreditAccountActivatedListener(PromoterAssignmentService assignmentService) {
        this.assignmentService = assignmentService;
    }

    @KafkaListener(topics = "credit-portfolio.credit-account-activated",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "creditAccountActivatedListenerContainerFactory")
    public void onMessage(CreditAccountActivatedPayload p) {
        log.debug("credit-account-activated creditAccountId={} promoterCode={}", p.creditAccountId(), p.promoterCode());
        assignmentService.onCreditAccountActivated(p.creditAccountId(), p.productType(), p.promoterCode());
    }
}
