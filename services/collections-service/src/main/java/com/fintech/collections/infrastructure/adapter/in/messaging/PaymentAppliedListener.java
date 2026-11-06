package com.fintech.collections.infrastructure.adapter.in.messaging;

import com.fintech.collections.application.PaymentOrigin;
import com.fintech.collections.application.service.PromiseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class PaymentAppliedListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentAppliedListener.class);

    private final PromiseService promiseService;

    public PaymentAppliedListener(PromiseService promiseService) {
        this.promiseService = promiseService;
    }

    @KafkaListener(topics = "payments.payment-applied",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "paymentAppliedListenerContainerFactory")
    public void onMessage(PaymentAppliedPayload payload) {
        log.debug("payment-applied received creditAccountId={} amount={}", payload.creditAccountId(), payload.amount());
        UUID paymentId = safeUuid(payload.eventId());
        // Where the money came from travels on with a recovery: accounting books it against that account.
        promiseService.onPaymentApplied(payload.creditAccountId(), paymentId, payload.amount(),
                new PaymentOrigin(payload.fundingSource(), payload.inboundCreditId()));
    }

    private static UUID safeUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (Exception e) {
            return null;
        }
    }
}
