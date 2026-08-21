package com.fintech.notifications.infrastructure.adapter.in.messaging;

import com.fintech.notifications.application.service.NotificationTriggerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class PaymentAppliedListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentAppliedListener.class);
    private final NotificationTriggerService triggerService;

    public PaymentAppliedListener(NotificationTriggerService triggerService) {
        this.triggerService = triggerService;
    }

    @KafkaListener(topics = "payments.payment-applied",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "paymentAppliedListenerContainerFactory")
    public void onMessage(PaymentAppliedPayload p) {
        log.debug("payment-applied creditAccountId={} amount={}", p.creditAccountId(), p.amount());
        triggerService.onPaymentApplied(p.eventId(), p.creditAccountId(), p.amount());
    }
}
