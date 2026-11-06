package com.fintech.origination.infrastructure.adapter.in.messaging;

import com.fintech.origination.application.port.in.ApplyDisbursementUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * credit-portfolio.credit-account-cancelled → the application closes as a failed disbursement (D63). It was marked
 * disbursed when the credit was activated, before any money left: the credit never lent anything.
 */
@Component
public class CreditAccountCancelledEventListener {

    private static final Logger log = LoggerFactory.getLogger(CreditAccountCancelledEventListener.class);

    private final ApplyDisbursementUseCase applyDisbursement;

    public CreditAccountCancelledEventListener(ApplyDisbursementUseCase applyDisbursement) {
        this.applyDisbursement = applyDisbursement;
    }

    @KafkaListener(
            topics = "credit-portfolio.credit-account-cancelled",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "creditAccountCancelledListenerContainerFactory")
    public void onMessage(CreditAccountCancelledPayload payload) {
        log.info("CreditAccountCancelled received contractId={} creditAccountId={} reason={}",
                payload.contractId(), payload.creditAccountId(), payload.reason());
        if (payload.contractId() == null) {
            log.warn("CreditAccountCancelled sin contractId — no hay solicitud que cerrar");
            return;
        }
        applyDisbursement.applyDisbursementFailed(payload.contractId());
    }
}
