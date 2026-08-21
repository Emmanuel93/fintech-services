package com.fintech.payments.infrastructure.adapter.out.messaging;

import com.fintech.payments.application.port.out.PaymentEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

@Component
public class KafkaPaymentEventPublisher implements PaymentEventPublisher {

    static final String TOPIC_PAYMENT_APPLIED  = "payments.payment-applied";
    static final String TOPIC_PAYMENT_REVERSED = "payments.payment-returned";

    private static final Logger log = LoggerFactory.getLogger(KafkaPaymentEventPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaPaymentEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publishPaymentApplied(String eventId, UUID creditAccountId,
                                       BigDecimal amount, long snapshotVersion) {
        var payload = new PaymentAppliedOutboundPayload(eventId, creditAccountId, amount, snapshotVersion);
        kafkaTemplate.send(TOPIC_PAYMENT_APPLIED, creditAccountId.toString(), payload)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("payment-applied publish failed eventId={}: {}", eventId, ex.getMessage());
                    } else {
                        log.info("payment-applied published eventId={} creditAccountId={} amount={} v={}",
                                eventId, creditAccountId, amount, snapshotVersion);
                    }
                });
    }

    @Override
    public void publishPaymentReversed(String eventId, UUID creditAccountId,
                                        BigDecimal amount, String reason) {
        var payload = new PaymentReversedOutboundPayload(eventId, creditAccountId, amount, reason);
        kafkaTemplate.send(TOPIC_PAYMENT_REVERSED, creditAccountId.toString(), payload)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("payment-returned publish failed eventId={}: {}", eventId, ex.getMessage());
                    } else {
                        log.info("payment-returned published eventId={} creditAccountId={} amount={}",
                                eventId, creditAccountId, amount);
                    }
                });
    }
}
