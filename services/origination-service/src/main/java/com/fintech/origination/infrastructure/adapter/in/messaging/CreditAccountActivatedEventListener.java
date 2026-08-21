package com.fintech.origination.infrastructure.adapter.in.messaging;

import com.fintech.origination.application.port.in.ApplyDisbursementUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class CreditAccountActivatedEventListener {

    private static final Logger log = LoggerFactory.getLogger(CreditAccountActivatedEventListener.class);

    private final ApplyDisbursementUseCase applyDisbursement;

    public CreditAccountActivatedEventListener(ApplyDisbursementUseCase applyDisbursement) {
        this.applyDisbursement = applyDisbursement;
    }

    @KafkaListener(
            topics = "credit-portfolio.credit-account-activated",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "creditAccountActivatedListenerContainerFactory")
    public void onMessage(CreditAccountActivatedPayload payload) {
        log.info("CreditAccountActivated received contractId={} creditAccountId={}",
                payload.contractId(), payload.creditAccountId());
        if (payload.contractId() == null) {
            log.warn("CreditAccountActivated missing contractId — cannot close loop");
            return;
        }
        applyDisbursement.apply(payload.contractId());
    }
}
