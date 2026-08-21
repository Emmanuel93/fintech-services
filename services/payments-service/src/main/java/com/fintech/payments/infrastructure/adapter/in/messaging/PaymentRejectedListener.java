package com.fintech.payments.infrastructure.adapter.in.messaging;

import com.fintech.payments.application.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class PaymentRejectedListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentRejectedListener.class);

    private final PaymentService paymentService;

    public PaymentRejectedListener(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @KafkaListener(
            topics = "credit-portfolio.payment-rejected",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "paymentRejectedListenerContainerFactory")
    public void onPaymentRejected(PaymentRejectedPayload event) {
        log.warn("payment-rejected received sourceEventId={} creditAccountId={} reason={}",
                event.sourceEventId(), event.creditAccountId(), event.reason());
        paymentService.rejectByEventId(event.sourceEventId(), event.reason());
    }
}
