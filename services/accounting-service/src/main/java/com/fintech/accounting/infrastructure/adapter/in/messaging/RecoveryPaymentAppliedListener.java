package com.fintech.accounting.infrastructure.adapter.in.messaging;

import com.fintech.accounting.application.service.PostingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class RecoveryPaymentAppliedListener {

    private static final Logger log = LoggerFactory.getLogger(RecoveryPaymentAppliedListener.class);
    private final PostingService postingService;

    public RecoveryPaymentAppliedListener(PostingService postingService) {
        this.postingService = postingService;
    }

    @KafkaListener(topics = "collections.recovery-payment-applied",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "recoveryPaymentAppliedListenerContainerFactory")
    public void onMessage(RecoveryPaymentAppliedPayload p) {
        log.debug("recovery-payment-applied creditAccountId={} amount={}", p.creditAccountId(), p.recoveredAmount());
        postingService.onRecoveryPayment("RECOVERY-" + p.writeOffId(), p.creditAccountId(), null,
                p.recoveredAmount(), p.occurredOn());
    }
}
