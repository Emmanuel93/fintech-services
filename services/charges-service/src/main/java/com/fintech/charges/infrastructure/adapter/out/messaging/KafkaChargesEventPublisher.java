package com.fintech.charges.infrastructure.adapter.out.messaging;

import com.fintech.charges.application.port.out.ChargeEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

@Component
public class KafkaChargesEventPublisher implements ChargeEventPublisher {

    static final String TOPIC_CHARGE_APPLIED  = "charges.charge-applied";
    static final String TOPIC_CHARGE_REVERSED = "charges.charge-reversed";

    private static final Logger log = LoggerFactory.getLogger(KafkaChargesEventPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaChargesEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publishChargeApplied(String eventId, UUID creditAccountId,
                                      String chargeType, BigDecimal totalAmount,
                                      java.time.LocalDate effectiveDate) {
        var payload = new ChargeAppliedOutboundPayload(eventId, creditAccountId, chargeType, totalAmount,
                effectiveDate);
        kafkaTemplate.send(TOPIC_CHARGE_APPLIED, creditAccountId.toString(), payload)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("charge-applied publish failed chargeId={} type={}: {}",
                                eventId, chargeType, ex.getMessage());
                    } else {
                        log.debug("charge-applied published chargeId={} type={} amount={}",
                                eventId, chargeType, totalAmount);
                    }
                });
    }

    @Override
    public void publishChargeReversed(String eventId, UUID creditAccountId,
                                       String originalChargeType, BigDecimal amount, boolean waived) {
        var payload = new ChargeReversedOutboundPayload(eventId, creditAccountId,
                originalChargeType, amount, waived);
        kafkaTemplate.send(TOPIC_CHARGE_REVERSED, creditAccountId.toString(), payload)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("charge-reversed publish failed chargeId={} waived={}: {}",
                                eventId, waived, ex.getMessage());
                    } else {
                        log.debug("charge-reversed published chargeId={} waived={}", eventId, waived);
                    }
                });
    }
}
