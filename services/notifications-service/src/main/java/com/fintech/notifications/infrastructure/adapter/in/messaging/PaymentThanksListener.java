package com.fintech.notifications.infrastructure.adapter.in.messaging;

import com.fintech.notifications.application.service.NotificationTriggerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Component
public class PaymentThanksListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentThanksListener.class);
    private final NotificationTriggerService triggerService;

    public PaymentThanksListener(NotificationTriggerService triggerService) {
        this.triggerService = triggerService;
    }

    @KafkaListener(topics = "collections.payment-thanks",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "paymentThanksListenerContainerFactory")
    public void onMessage(PaymentThanksPayload p) {
        log.debug("payment-thanks caseId={} amount={}", p.caseId(), p.amount());
        String sourceEventId = "thanks:" + p.caseId() + ":" + p.occurredAt();
        triggerService.onPaymentThanks(sourceEventId, p.caseId(), p.obligorPartyId(),
                p.amount(), p.daysDelinquent());
    }

    public record PaymentThanksPayload(
            UUID caseId,
            UUID creditAccountId,
            UUID obligorPartyId,
            BigDecimal amount,
            int daysDelinquent,
            Instant occurredAt
    ) {}
}
