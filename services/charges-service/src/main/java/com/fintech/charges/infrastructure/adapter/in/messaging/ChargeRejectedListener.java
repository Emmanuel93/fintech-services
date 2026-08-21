package com.fintech.charges.infrastructure.adapter.in.messaging;

import com.fintech.charges.application.port.out.ChargeRecordRepository;
import com.fintech.charges.domain.ChargeRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Consumes {@code credit-portfolio.charge-rejected} and reverses the ChargeRecord
 * that was optimistically persisted by the accrual job.
 * This is the post-check callback for the dual-validation balance control pattern.
 */
@Component
public class ChargeRejectedListener {

    private static final Logger log = LoggerFactory.getLogger(ChargeRejectedListener.class);

    private final ChargeRecordRepository chargeRecordRepository;

    public ChargeRejectedListener(ChargeRecordRepository chargeRecordRepository) {
        this.chargeRecordRepository = chargeRecordRepository;
    }

    @Transactional
    @KafkaListener(
            topics = "credit-portfolio.charge-rejected",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "chargeRejectedListenerContainerFactory")
    public void onChargeRejected(ChargeRejectedPayload event) {
        log.warn("charge-rejected received sourceEventId={} creditAccountId={} type={} reason={}",
                event.sourceEventId(), event.creditAccountId(), event.chargeType(), event.reason());

        if (event.sourceEventId() == null) {
            log.warn("charge-rejected with null sourceEventId — cannot match ChargeRecord, skipping");
            return;
        }

        // sourceEventId is the chargeId that was published in the charge-applied event
        UUID chargeId;
        try {
            chargeId = UUID.fromString(event.sourceEventId());
        } catch (IllegalArgumentException ex) {
            log.error("charge-rejected sourceEventId={} is not a valid UUID — skipping", event.sourceEventId());
            return;
        }

        chargeRecordRepository.findById(chargeId).ifPresentOrElse(charge -> {
            if (charge.isApplied()) {
                charge.reverse("CHARGE_REJECTED: " + event.reason());
                chargeRecordRepository.save(charge);
                log.info("ChargeRecord {} reversed due to charge-rejected from portfolio", chargeId);
            } else {
                log.info("ChargeRecord {} already in status={} — no reversal needed", chargeId, charge.getStatus());
            }
        }, () -> log.warn("ChargeRecord {} not found — charge-rejected event has no target to reverse", chargeId));
    }
}
